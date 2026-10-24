/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.embeddings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import opennlp.embeddings.spi.TeacherEncoder;
import opennlp.embeddings.spi.TeacherEncoderProvider;
import opennlp.tools.util.ParamChecks;
import opennlp.tools.util.ext.ProviderSpec;
import opennlp.tools.util.ext.Providers;
import opennlp.tools.util.java.Experimental;

/**
 * Distills a sentence-transformer teacher into a static embedding table in the layout
 * {@link StaticEmbeddingModel#load(Path)} opens, reproducing
 * <a href="https://github.com/MinishLab/model2vec">Model2Vec</a>'s distillation in Java so no
 * Python environment is needed. The pipeline is Model2Vec's:
 *
 * <ol>
 *   <li>The teacher's vocabulary is cleaned (unused tokens and special added tokens other than
 *   the unknown and pad tokens are dropped, the rest keeps its id order) and every surviving
 *   token is run through the teacher's ONNX graph as {@code [bos, token, eos]}; the token's
 *   embedding is the mean of the last hidden states.</li>
 *   <li>The matrix is projected onto its top principal components with the randomized SVD in
 *   {@link RandomizedPca}.</li>
 *   <li>Each row is scaled by its Zipf weight {@code sif / (sif + p)}, where {@code p} is the
 *   row's share of a Zipf distribution over the vocabulary and {@code sif} is
 *   {@value #SIF_COEFFICIENT}, Model2Vec's default.</li>
 *   <li>The result is written as {@code model.safetensors} (F32), the cleaned
 *   {@code tokenizer.json}, and a {@code config.json} with {@code "normalize": true}; a
 *   SentencePiece teacher's {@code .model} file is copied alongside. The directory is then
 *   completed and verified by {@link ModelAssembler}.</li>
 * </ol>
 *
 * <p>The teacher directory must hold {@code tokenizer.json} and {@code onnx/model.onnx} (the
 * ONNX export every sentence-transformer ships on the Hugging Face hub); a local
 * {@code tokenizer_config.json} supplies the pad token when present.</p>
 *
 * <p>The teacher must produce a consistent vector length across batches. A change in length
 * causes an {@link opennlp.tools.embeddings.EmbeddingException} before output files are
 * written.</p>
 *
 * <p>Warning: Experimental new feature; the API might change in a later release.</p>
 */
@Experimental
public final class ModelDistiller {

  /** Model2Vec's default SIF coefficient for the Zipf weighting. */
  static final double SIF_COEFFICIENT = 1e-4;

  /** The number of id sequences per forward-pass batch, Model2Vec's batch size. */
  private static final int BATCH_SIZE = 256;

  /** The fixed seed of the PCA range finder, so a distillation is reproducible. */
  private static final long PCA_SEED = 42;

  /** Receives progress messages; the command-line tool prints them. */
  @FunctionalInterface
  public interface ProgressListener {

    /**
     * Reports a progress message.
     *
     * @param message The message.
     */
    void progress(String message);
  }

  /**
   * The outcome of a distillation: the family, size, and dimension of the verified model, plus
   * the variance the PCA kept.
   *
   * @param family                  {@code "WordPiece"} or {@code "SentencePiece"}.
   * @param vocabularySize          The number of subword rows in the distilled table.
   * @param termCount               The number of term rows appended after the subword rows.
   * @param teacherDimension        The teacher's hidden dimension.
   * @param dimension               The distilled table's dimension (after PCA).
   * @param explainedVarianceRatio  The share of the embedding variance the PCA kept.
   */
  public record Result(String family, int vocabularySize, int termCount, int teacherDimension,
                       int dimension, double explainedVarianceRatio) {
  }

  private final int pcaDims;
  private final ProgressListener listener;
  private final Providers<TeacherEncoderProvider> encoders;

  /**
   * Creates a distiller that runs teachers through the {@link TeacherEncoderProvider} the
   * {@link Providers#of(Class) default lookup} selects for their ONNX file.
   *
   * @param pcaDims  The number of principal components to keep; clamped to the teacher's hidden
   *                 dimension, and skipped entirely when it would not reduce a tiny vocabulary.
   *                 Model2Vec's default (and the recommended value) is 256. Must be positive.
   * @param listener Receives one progress line per distillation phase, per forward-pass batch
   *                 and per download; may be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code pcaDims} is not positive.
   */
  public ModelDistiller(int pcaDims, ProgressListener listener) {
    this(pcaDims, listener, Providers.of(TeacherEncoderProvider.class));
  }

  /**
   * Creates a distiller that runs teachers through the {@link TeacherEncoderProvider} the given
   * lookup selects for their ONNX file, for an application that looks providers up with its own
   * class loader or configuration.
   *
   * @param pcaDims  The number of principal components to keep, as in
   *                 {@link #ModelDistiller(int, ProgressListener)}. Must be positive.
   * @param listener Receives progress lines; may be {@code null}.
   * @param encoders The teacher encoder providers. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code pcaDims} is not positive or
   *     {@code encoders} is {@code null}.
   */
  public ModelDistiller(int pcaDims, ProgressListener listener,
                        Providers<TeacherEncoderProvider> encoders) {
    this.pcaDims = ParamChecks.requirePositive(pcaDims, "pcaDims");
    this.listener = listener;
    this.encoders = ParamChecks.requireNonNullArg(encoders, "encoders");
  }

  /**
   * Distills a teacher into a model directory, resolving the teacher reference first: a local
   * directory is used as-is, a Hugging Face model id ({@code org/model}, or
   * {@code org/model@revision} to pin a revision) is downloaded into a local cache on first use.
   *
   * @param teacher         The teacher: a local directory or a Hugging Face model id. Must not
   *                        be {@code null}.
   * @param outputDirectory The model directory to write, as in
   *                        {@link #distill(Path, Path, List)}. Must not be {@code null}.
   * @return The distillation result, read back from the verified directory.
   * @throws IllegalArgumentException Thrown if an argument is {@code null} or invalid, or the
   *     teacher reference is malformed.
   * @throws IOException Thrown if reading or writing a file fails, or if a teacher cannot be
   *     downloaded and verified.
   */
  public Result distill(String teacher, Path outputDirectory) throws IOException {
    return distill(teacher, outputDirectory, List.of());
  }

  /**
   * Distills a teacher into a model directory with additional term rows, resolving the teacher
   * reference the way {@link #distill(String, Path)} does.
   *
   * @param teacher         The teacher: a local directory or a Hugging Face model id. Must not
   *                        be {@code null}.
   * @param outputDirectory The model directory to write. Must not be {@code null}.
   * @param terms           The terms to distill as extra rows; see
   *                        {@link #distill(Path, Path, List)}. Must not be {@code null}.
   * @return The distillation result, read back from the verified directory.
   * @throws IllegalArgumentException Thrown if an argument is {@code null} or invalid, a term
   *     normalizes to nothing, or the teacher reference is malformed.
   * @throws IOException Thrown if reading or writing a file fails, or if a teacher cannot be
   *     downloaded and verified.
   */
  public Result distill(String teacher, Path outputDirectory, List<String> terms)
      throws IOException {
    checkOutput(outputDirectory);
    final List<String> prepared = prepareTerms(terms);
    return distill(new HuggingFaceModelCache(listener).resolve(teacher), outputDirectory, prepared);
  }

  /**
   * Distills a teacher into a model directory.
   *
   * @param teacherDirectory The teacher's directory, holding {@code tokenizer.json} and
   *                         {@code onnx/model.onnx}. Must not be {@code null} and must be a
   *                         directory.
   * @param outputDirectory  The model directory to write. Created when missing. Files produced
   *                         or derived by distillation are replaced; unrelated files remain. A
   *                         failure part way through leaves an incomplete output directory. Must
   *                         not be {@code null}.
   * @return The distillation result, read back from the verified directory.
   * @throws IllegalArgumentException Thrown if an argument is {@code null} or invalid, the
   *     teacher and output are the same directory, or the teacher directory lacks its files.
   * @throws IOException Thrown if reading or writing a file fails.
   */
  public Result distill(Path teacherDirectory, Path outputDirectory) throws IOException {
    return distill(teacherDirectory, outputDirectory, List.of());
  }

  /**
   * Distills a teacher into a model directory with additional term rows: whole words and
   * multi-word phrases (a learned corpus vocabulary) that are segmented by the teacher's own
   * tokenizer, run through the teacher as full sequences, and appended to the table after the
   * subword rows. The loaded model then matches text against these terms greedily
   * longest-first before falling back to subword pieces.
   *
   * <p>Each term is normalized to lower-cased words joined by single spaces before use; terms
   * that normalize to the same form are distilled once, and a term equal to a surviving
   * vocabulary token is dropped, because its row would duplicate that token's. The terms are
   * written to the model directory as {@code terms.txt}, one per line in row order, and should
   * arrive sorted by descending corpus frequency: the Zipf weighting spans the subword rows and
   * the term rows as one ranking.</p>
   *
   * @param teacherDirectory The teacher's directory, as in {@link #distill(Path, Path)}. A
   *                         Unigram teacher must also hold its trained SentencePiece
   *                         {@code .model} file. Must not be {@code null}.
   * @param outputDirectory  The model directory to write, as in {@link #distill(Path, Path)}.
   *                         Must not be {@code null}.
   * @param terms            The terms to distill as extra rows; empty for none. Must not be
   *                         {@code null} and must not contain {@code null}.
   * @return The distillation result, read back from the verified directory.
   * @throws IllegalArgumentException Thrown if an argument is {@code null} or invalid, a term
   *     normalizes to nothing, the teacher and output are the same directory, or the teacher
   *     directory lacks its files.
   * @throws opennlp.tools.util.ext.ProviderResolutionException Thrown if no available provider
   *     supports the teacher's ONNX file, or if several share the highest priority.
   * @throws opennlp.tools.embeddings.EmbeddingException Thrown if the teacher fails to encode.
   * @throws IOException Thrown if reading or writing a file fails, including the teacher's ONNX
   *     file.
   */
  public Result distill(Path teacherDirectory, Path outputDirectory, List<String> terms)
      throws IOException {
    ParamChecks.requireNonNullArg(teacherDirectory, "teacherDirectory");
    if (!Files.isDirectory(teacherDirectory)) {
      throw new IllegalArgumentException("Teacher directory does not exist or is not a "
          + "directory: " + teacherDirectory);
    }
    checkOutput(outputDirectory);
    if (Files.exists(outputDirectory) && Files.isSameFile(teacherDirectory, outputDirectory)) {
      throw new IllegalArgumentException("outputDirectory must differ from teacherDirectory");
    }
    final Path onnxFile = teacherDirectory.resolve(ModelFileNames.ONNX_MODEL);
    if (!Files.isRegularFile(onnxFile)) {
      throw new IllegalArgumentException("Teacher directory " + teacherDirectory + " has no "
          + ModelFileNames.ONNX_MODEL + "; the distillation runs the teacher's ONNX export, which "
          + "sentence-transformers ship on the Hugging Face hub");
    }
    final TeacherTokenizer tokenizer = TeacherTokenizer.read(
        teacherDirectory.resolve(ModelFileNames.TOKENIZER_JSON),
        teacherDirectory.resolve(ModelFileNames.TOKENIZER_CONFIG));
    final int rows = tokenizer.vocabularySize();
    final List<String> termList = new ArrayList<>(prepareTerms(terms));
    if (!termList.isEmpty()) {
      // A term equal to a surviving vocabulary token would encode to the same teacher sequence
      // and duplicate that token's row, so it is dropped; matching then reaches the token's row
      // through the subword fallback instead.
      final Set<String> keptTokens = new HashSet<>(rows * 2);
      for (int row = 0; row < rows; row++) {
        keptTokens.add(tokenizer.rowToken(row));
      }
      final int requestedTerms = termList.size();
      termList.removeIf(keptTokens::contains);
      if (requestedTerms > termList.size()) {
        report("Dropped " + (requestedTerms - termList.size())
            + " terms already present as vocabulary tokens");
      }
    }
    final int totalRows = rows + termList.size();

    report("Encoding " + rows + " vocabulary tokens of " + teacherDirectory
        + " through its ONNX graph");
    final float[] embeddings;
    final int teacherDimension;
    final ProviderSpec spec = ProviderSpec.of(onnxFile);
    try (TeacherEncoder encoder = encoders.select(spec).create(spec)) {
      float[][] first = encoder.encodeBatch(new long[][] {tokenizer.inputSequence(0)});
      teacherDimension = first[0].length;
      embeddings = new float[totalRows * teacherDimension];
      System.arraycopy(first[0], 0, embeddings, 0, teacherDimension);
      int row = 1;
      while (row < rows) {
        final int batchSize = Math.min(BATCH_SIZE, rows - row);
        final long[][] batch = new long[batchSize][];
        for (int b = 0; b < batchSize; b++) {
          batch[b] = tokenizer.inputSequence(row + b);
        }
        final float[][] pooled = encoder.encodeBatch(batch);
        for (int b = 0; b < batchSize; b++) {
          System.arraycopy(pooled[b], 0, embeddings, (row + b) * teacherDimension,
              teacherDimension);
        }
        row += batchSize;
        report("Encoded " + row + " / " + rows + " vocabulary tokens");
      }
      encodeTerms(termList, tokenizer, teacherDirectory, encoder, embeddings, rows,
          teacherDimension);
    }
    nonFiniteToZero(embeddings);

    final int requested = Math.min(pcaDims, teacherDimension);
    final float[] transformed;
    final int components;
    double explainedVarianceRatio = 1.0;
    if (requested >= totalRows) {
      // A PCA with more components than rows is not a reduction; Model2Vec skips it with a
      // warning. Only reachable for toy vocabularies, which then keep the teacher's dimension.
      transformed = embeddings;
      components = teacherDimension;
    } else {
      report("Reducing " + totalRows + " x " + teacherDimension + " to " + requested
          + " principal components");
      final RandomizedPca.Result pca = RandomizedPca.fitTransform(embeddings, totalRows,
          teacherDimension, requested, PCA_SEED);
      transformed = pca.transformed();
      components = requested;
      explainedVarianceRatio = pca.explainedVarianceRatio();
    }
    final float[] weights = zipfWeights(totalRows, SIF_COEFFICIENT);
    for (int row = 0; row < totalRows; row++) {
      final int base = row * components;
      final float weight = weights[row];
      for (int d = 0; d < components; d++) {
        transformed[base + d] *= weight;
      }
    }

    report("Writing and verifying the model directory " + outputDirectory);
    Files.createDirectories(outputDirectory);
    removeDerivedArtifacts(outputDirectory);
    SafetensorsWriter.writeMatrix(outputDirectory.resolve(ModelFileNames.SAFETENSORS), totalRows,
        components, transformed);
    tokenizer.writeCleaned(outputDirectory.resolve(ModelFileNames.TOKENIZER_JSON));
    Files.writeString(outputDirectory.resolve(ModelFileNames.CONFIG),
        configJson(teacherDirectory, components));
    copySentencePieceModel(teacherDirectory, outputDirectory);
    final Path termsFile = outputDirectory.resolve(ModelFileNames.TERMS);
    if (termList.isEmpty()) {
      // The current matrix has no term rows, so an existing terms file cannot describe it.
      Files.deleteIfExists(termsFile);
    } else {
      Files.write(termsFile, termList);
    }
    final ModelAssembler.Result assembled = ModelAssembler.assemble(outputDirectory);
    return new Result(assembled.family(), assembled.vocabularySize(), assembled.termCount(),
        teacherDimension, assembled.dimension(), explainedVarianceRatio);
  }

  /**
   * Removes tokenizer files derived or copied by an earlier distillation. They must describe the
   * same tokenizer as the matrix and {@code tokenizer.json} written by the current run.
   *
   * @param outputDirectory The model output directory.
   * @throws IOException Thrown if an old artifact cannot be removed.
   */
  private void removeDerivedArtifacts(Path outputDirectory) throws IOException {
    Files.deleteIfExists(outputDirectory.resolve(ModelFileNames.VOCABULARY));
    Files.deleteIfExists(outputDirectory.resolve(ModelFileNames.TOKENIZER_CONFIG));
    for (final String name : ModelFileNames.SENTENCEPIECE_MODELS) {
      Files.deleteIfExists(outputDirectory.resolve(name));
    }
  }

  /**
   * Encodes the term rows: each term is segmented by the teacher's own tokenizer, wrapped as a
   * full input sequence, and mean-pooled through the teacher, filling the matrix rows after the
   * vocabulary rows. Sequences vary in length and a batch must not be ragged, so equal-length
   * sequences are batched together.
   *
   * @param termList         The normalized terms, in row order.
   * @param tokenizer        The teacher's parsed tokenizer.
   * @param teacherDirectory The teacher's directory, for the segmenter.
   * @param encoder          The open teacher encoder.
   * @param embeddings       The matrix being filled, {@code totalRows * teacherDimension}.
   * @param vocabularyRows   The number of vocabulary rows preceding the term rows.
   * @param teacherDimension The teacher's hidden dimension.
   * @throws IOException Thrown if reading the teacher's SentencePiece file fails.
   */
  private void encodeTerms(List<String> termList, TeacherTokenizer tokenizer,
                           Path teacherDirectory, TeacherEncoder encoder,
                           float[] embeddings, int vocabularyRows, int teacherDimension)
      throws IOException {
    if (termList.isEmpty()) {
      return;
    }
    report("Encoding " + termList.size()
        + " terms through the teacher's own segmentation");
    final TermSegmenter segmenter = TermSegmenter.forTeacher(tokenizer, teacherDirectory);
    final long[][] sequences = new long[termList.size()][];
    for (int t = 0; t < sequences.length; t++) {
      sequences[t] = tokenizer.inputSequence(segmenter.pieces(termList.get(t)));
    }
    final Integer[] byLength = new Integer[sequences.length];
    for (int t = 0; t < byLength.length; t++) {
      byLength[t] = t;
    }
    Arrays.sort(byLength, Comparator.comparingInt(t -> sequences[t].length));
    int encoded = 0;
    while (encoded < byLength.length) {
      int end = encoded + 1;
      while (end < byLength.length && end - encoded < BATCH_SIZE
          && sequences[byLength[end]].length == sequences[byLength[encoded]].length) {
        end++;
      }
      final long[][] batch = new long[end - encoded][];
      for (int b = 0; b < batch.length; b++) {
        batch[b] = sequences[byLength[encoded + b]];
      }
      final float[][] pooled = encoder.encodeBatch(batch);
      for (int b = 0; b < batch.length; b++) {
        System.arraycopy(pooled[b], 0, embeddings,
            (vocabularyRows + byLength[encoded + b]) * teacherDimension, teacherDimension);
      }
      encoded = end;
      report("Encoded " + encoded + " / " + termList.size() + " terms");
    }
  }

  /**
   * Normalizes and deduplicates the requested terms before any teacher work: each term becomes
   * its lower-cased words joined by single spaces, and terms normalizing to the same form are
   * kept once, in first-occurrence order.
   *
   * @param terms The requested terms.
   * @return The normalized, duplicate-free terms.
   * @throws IllegalArgumentException Thrown if {@code terms} is {@code null}, contains
   *     {@code null}, or contains a term with no letter or digit.
   */
  private List<String> prepareTerms(List<String> terms) {
    ParamChecks.requireNonNullArg(terms, "terms");
    final Set<String> prepared = new LinkedHashSet<>(terms.size() * 2);
    for (int termIndex = 0; termIndex < terms.size(); termIndex++) {
      final String term = ParamChecks.requireNonNullArg(terms.get(termIndex),
          "terms[" + termIndex + "]");
      final String normalized = TermTable.normalizeTerm(term);
      if (normalized.isEmpty()) {
        throw new IllegalArgumentException("Term '" + term
            + "' has no letter or digit; it cannot be matched in text");
      }
      prepared.add(normalized);
    }
    return List.copyOf(prepared);
  }

  /**
   * Validates the output directory before any teacher work, so that a distillation naming a
   * hub teacher fails before it downloads anything.
   *
   * @param outputDirectory The model directory to write.
   * @throws IllegalArgumentException Thrown if the directory is {@code null}, or exists and is
   *     not a directory.
   */
  private void checkOutput(Path outputDirectory) {
    ParamChecks.requireNonNullArg(outputDirectory, "outputDirectory");
    if (Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)
        && !Files.isDirectory(outputDirectory)) {
      throw new IllegalArgumentException(
          "outputDirectory must be a directory or not exist: " + outputDirectory);
    }
  }

  /**
   * Reports one progress line, if anyone is listening.
   *
   * @param message The message.
   */
  private void report(String message) {
    if (listener != null) {
      listener.progress(message);
    }
  }

  /**
   * {@return Model2Vec's Zipf weights: row {@code i} gets {@code sif / (sif + p_i)} with
   * {@code p_i = (1 / (i + 2)) / sum_j (1 / (j + 2))}, a SIF weighting under the assumption that
   * vocabulary order approximates frequency order (Zipf's law)}
   *
   * @param rows           The number of rows.
   * @param sifCoefficient The SIF coefficient.
   */
  float[] zipfWeights(int rows, double sifCoefficient) {
    double harmonicSum = 0;
    for (int j = 2; j <= rows + 1; j++) {
      harmonicSum += 1.0 / j;
    }
    final float[] weights = new float[rows];
    for (int i = 0; i < rows; i++) {
      final double probability = 1.0 / (i + 2) / harmonicSum;
      weights[i] = (float) (sifCoefficient / (sifCoefficient + probability));
    }
    return weights;
  }

  /**
   * Replaces non-finite values with zero, the guard against a teacher emitting a NaN or infinite
   * hidden state. Model2Vec applies numpy's {@code nan_to_num} here, which maps an infinity to the
   * largest finite float; zero is used instead because an infinity of that magnitude still leaves
   * the principal component analysis with nothing but that one row.
   *
   * @param values The matrix, modified in place.
   */
  private void nonFiniteToZero(float[] values) {
    for (int i = 0; i < values.length; i++) {
      if (!Float.isFinite(values[i])) {
        values[i] = 0;
      }
    }
  }

  /**
   * {@return the {@code config.json} of the distilled model, mirroring the fields Model2Vec
   * writes; the loader reads only {@code normalize}}
   *
   * @param teacherDirectory The teacher's directory, for the name.
   * @param components       The effective PCA dimension.
   * @throws IOException Thrown if the teacher's revision file cannot be read.
   */
  private String configJson(Path teacherDirectory, int components)
      throws IOException {
    final Path name = teacherDirectory.getFileName();
    return "{\n"
        + "  \"model_type\": \"model2vec\",\n"
        + "  \"architectures\": [\"StaticModel\"],\n"
        + "  \"tokenizer_name\": "
        + JsonStrings.quote(String.valueOf(name == null ? teacherDirectory : name)) + ",\n"
        + teacherRevisionField(teacherDirectory)
        + "  \"apply_pca\": " + pcaDims + ",\n"
        + "  \"sif_coefficient\": " + SIF_COEFFICIENT + ",\n"
        + "  \"hidden_dim\": " + components + ",\n"
        + "  \"seq_length\": 1000000,\n"
        + "  \"normalize\": true,\n"
        + "  \"pooling\": \"mean\",\n"
        + "  \"embedding_dtype\": \"float32\"\n"
        + "}\n";
  }

  /**
   * {@return the {@code config.json} field naming the commit the teacher's files came from, or an
   * empty string when the teacher directory is not a cached hub download}
   *
   * <p>A branch or tag may later identify different model files, so the output records the exact
   * teacher revision used for the distillation.</p>
   *
   * @param teacherDirectory The teacher's directory.
   * @throws IOException Thrown if the teacher's revision file cannot be read.
   */
  private String teacherRevisionField(Path teacherDirectory) throws IOException {
    final String revision = HuggingFaceModelCache.pinnedRevision(teacherDirectory);
    return revision == null ? "" : "  \"teacher_revision\": " + JsonStrings.quote(revision) + ",\n";
  }

  /**
   * Copies the teacher's trained SentencePiece {@code .model} file into the model directory when
   * the teacher has one; the distillation cannot fabricate it and the loader needs it for the
   * SentencePiece layout.
   *
   * @param teacherDirectory The teacher's directory.
   * @param outputDirectory  The model directory.
   * @throws IOException Thrown if copying fails.
   */
  private void copySentencePieceModel(Path teacherDirectory, Path outputDirectory)
      throws IOException {
    for (final String name : ModelFileNames.SENTENCEPIECE_MODELS) {
      final Path source = teacherDirectory.resolve(name);
      if (Files.isRegularFile(source)) {
        Files.copy(source, outputDirectory.resolve(name),
            StandardCopyOption.REPLACE_EXISTING);
        return;
      }
    }
  }
}
