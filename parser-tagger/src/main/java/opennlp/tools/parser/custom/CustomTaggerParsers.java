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

package opennlp.tools.parser.custom;

import opennlp.tools.chunker.Chunker;
import opennlp.tools.parser.AbstractBottomUpParser;
import opennlp.tools.parser.ParserModel;
import opennlp.tools.parser.ParserType;
import opennlp.tools.postag.POSTagger;

/**
 * Creates the core bottom-up parsers with a caller-supplied {@link POSTagger}, and
 * optionally a caller-supplied {@link Chunker}, in place of the ones built from the
 * {@link ParserModel}. Any tagger whose {@link POSTagger#topKSequences(String[])} returns
 * scored sequences can drive the parse.
 *
 * <p>Each parser is a subclass of the core parser for the model's {@link ParserType}. The
 * core constructor still builds its default tagger and chunker from the model; they are
 * replaced before the parser is returned, so they are never used.</p>
 *
 * @since 3.0.0
 */
public final class CustomTaggerParsers {

  private static final String MODEL_NULL = "model must not be null";
  private static final String TAGGER_NULL = "tagger must not be null";
  private static final String CHUNKER_NULL = "chunker must not be null";

  private CustomTaggerParsers() {
  }

  /**
   * Creates a parser for the model's type that tags with {@code tagger} and chunks with
   * the model's chunker, using the default beam size and advance percentage.
   *
   * @param model  The {@link ParserModel}. Must not be {@code null}.
   * @param tagger The {@link POSTagger} used to tag. Must not be {@code null}.
   * @return A parser that uses {@code tagger}.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}.
   * @throws IllegalStateException Thrown if the model's {@link ParserType} is not supported.
   */
  public static AbstractBottomUpParser create(ParserModel model, POSTagger tagger) {
    return create(model, tagger, AbstractBottomUpParser.defaultBeamSize,
        AbstractBottomUpParser.defaultAdvancePercentage);
  }

  /**
   * Creates a parser for the model's type that tags with {@code tagger} and chunks with
   * the model's chunker.
   *
   * @param model             The {@link ParserModel}. Must not be {@code null}.
   * @param tagger            The {@link POSTagger} used to tag. Must not be {@code null}.
   * @param beamSize          The number of different parses kept during parsing.
   * @param advancePercentage The minimal amount of probability mass which advanced outcomes
   *                          must represent.
   * @return A parser that uses {@code tagger}.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}.
   * @throws IllegalStateException Thrown if the model's {@link ParserType} is not supported.
   */
  public static AbstractBottomUpParser create(ParserModel model, POSTagger tagger,
                                              int beamSize, double advancePercentage) {
    requireNonNull(tagger, TAGGER_NULL);
    return build(model, tagger, null, beamSize, advancePercentage);
  }

  /**
   * Creates a parser for the model's type that tags with {@code tagger} and chunks with
   * {@code chunker}.
   *
   * @param model             The {@link ParserModel}. Must not be {@code null}.
   * @param tagger            The {@link POSTagger} used to tag. Must not be {@code null}.
   * @param chunker           The {@link Chunker} used to chunk. Must not be {@code null}.
   * @param beamSize          The number of different parses kept during parsing.
   * @param advancePercentage The minimal amount of probability mass which advanced outcomes
   *                          must represent.
   * @return A parser that uses {@code tagger} and {@code chunker}.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}.
   * @throws IllegalStateException Thrown if the model's {@link ParserType} is not supported.
   */
  public static AbstractBottomUpParser create(ParserModel model, POSTagger tagger,
                                              Chunker chunker, int beamSize,
                                              double advancePercentage) {
    requireNonNull(tagger, TAGGER_NULL);
    requireNonNull(chunker, CHUNKER_NULL);
    return build(model, tagger, chunker, beamSize, advancePercentage);
  }

  /**
   * Creates the parser for the model's type with the supplied components.
   *
   * @param model             The {@link ParserModel}. Must not be {@code null}.
   * @param tagger            The {@link POSTagger} used to tag. Never {@code null} here.
   * @param chunker           The {@link Chunker} used to chunk, or {@code null} to keep the
   *                          model's chunker.
   * @param beamSize          The number of different parses kept during parsing.
   * @param advancePercentage The minimal amount of probability mass which advanced outcomes
   *                          must represent.
   * @return A parser that uses {@code tagger}, and {@code chunker} if given.
   * @throws IllegalArgumentException Thrown if {@code model} is {@code null}.
   * @throws IllegalStateException Thrown if the model's {@link ParserType} is not supported.
   */
  private static AbstractBottomUpParser build(ParserModel model, POSTagger tagger,
                                              Chunker chunker, int beamSize,
                                              double advancePercentage) {
    requireNonNull(model, MODEL_NULL);
    final ParserType type = model.getParserType();
    if (type == ParserType.CHUNKING) {
      return new ChunkingParser(model, tagger, chunker, beamSize, advancePercentage);
    } else if (type == ParserType.TREEINSERT) {
      return new TreeInsertParser(model, tagger, chunker, beamSize, advancePercentage);
    }
    throw new IllegalStateException("Unsupported parser type: " + type);
  }

  /**
   * Rejects a {@code null} argument.
   *
   * @param value   The argument to check.
   * @param message The exception message.
   * @throws IllegalArgumentException Thrown if {@code value} is {@code null}.
   */
  private static void requireNonNull(Object value, String message) {
    if (value == null) {
      throw new IllegalArgumentException(message);
    }
  }

  /** The chunking parser with the tagger, and the chunker if given, replaced. */
  private static final class ChunkingParser extends opennlp.tools.parser.chunking.Parser {

    /**
     * Builds the core parser from the model and replaces its tagger, and its chunker if
     * {@code chunker} is not {@code null}.
     *
     * @param model             The {@link ParserModel}.
     * @param tagger            The {@link POSTagger} used to tag.
     * @param chunker           The {@link Chunker} used to chunk, or {@code null}.
     * @param beamSize          The number of different parses kept during parsing.
     * @param advancePercentage The minimal amount of probability mass which advanced outcomes
     *                          must represent.
     */
    ChunkingParser(ParserModel model, POSTagger tagger, Chunker chunker, int beamSize,
                   double advancePercentage) {
      super(model, beamSize, advancePercentage);
      this.tagger = tagger;
      if (chunker != null) {
        this.chunker = chunker;
      }
    }
  }

  /** The tree insert parser with the tagger, and the chunker if given, replaced. */
  private static final class TreeInsertParser extends opennlp.tools.parser.treeinsert.Parser {

    /**
     * Builds the core parser from the model and replaces its tagger, and its chunker if
     * {@code chunker} is not {@code null}.
     *
     * @param model             The {@link ParserModel}.
     * @param tagger            The {@link POSTagger} used to tag.
     * @param chunker           The {@link Chunker} used to chunk, or {@code null}.
     * @param beamSize          The number of different parses kept during parsing.
     * @param advancePercentage The minimal amount of probability mass which advanced outcomes
     *                          must represent.
     */
    TreeInsertParser(ParserModel model, POSTagger tagger, Chunker chunker, int beamSize,
                     double advancePercentage) {
      super(model, beamSize, advancePercentage);
      this.tagger = tagger;
      if (chunker != null) {
        this.chunker = chunker;
      }
    }
  }
}
