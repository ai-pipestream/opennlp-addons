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
package opennlp.addons.eval.vector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import opennlp.addons.eval.EvalReport;
import opennlp.addons.eval.EvalRuns;
import opennlp.embeddings.StaticEmbeddingModel;
import opennlp.embeddings.corpus.CasePassage;
import opennlp.embeddings.corpus.DictionaryEntry;
import opennlp.embeddings.eval.SearchEvaluator;

/**
 * Runs {@link SearchEvaluator} over a static embedding model and a normalized passage corpus,
 * the production evaluation behind the {@code EvalVectorSearch} command of embeddings-cli.
 *
 * <p>Data: {@code <eval root>/vector-search/model/} holds a {@link StaticEmbeddingModel}
 * directory, {@code passages.jsonl} the normalized passages and {@code dictionary.tsv} the
 * dictionary entries, both as the embeddings corpus tools write them. The quantized index uses
 * 4 bits and the evaluation depth is 10, the command's defaults.</p>
 *
 * <p>The thresholds are regression floors: the fidelity of the 4-bit index against the exact
 * scan must stay high, and the half-passage retrieval, where the first half of a passage
 * queries its own passage, must keep finding the source. Definition-to-headword retrieval is
 * recorded with a low floor because it depends on the dictionary's coverage of the corpus.
 * The TSV report is the measurement.</p>
 */
class VectorSearchEvalTest {

  static final String DATASET = "vector-search";
  static final int BITS = 4;
  static final long SEED = 42L;
  static final int TOP_K = 10;

  static final double MIN_FIDELITY_RECALL = 0.80;
  static final double MIN_FIDELITY_AGREEMENT = 0.50;
  static final double MIN_HALF_PASSAGE_RECALL = 0.50;
  static final double MIN_HALF_PASSAGE_QUANTIZED_RECALL = 0.40;
  static final double MIN_DEFINITION_MRR = 0.02;

  @Test
  void testExactAndQuantizedSearch() throws IOException {
    final Path dataset = EvalRuns.assumeDirectory(EvalRuns.DATA.dataset(DATASET));
    final Path modelDirectory = EvalRuns.assumeDirectory(dataset.resolve("model"));
    final Path passagesFile = EvalRuns.assumeFile(dataset.resolve("passages.jsonl"));
    final Path dictionaryFile = EvalRuns.assumeFile(dataset.resolve("dictionary.tsv"));

    final StaticEmbeddingModel model = StaticEmbeddingModel.load(modelDirectory);
    final List<CasePassage> passages = CasePassage.readJsonl(passagesFile);
    final List<DictionaryEntry> dictionary = DictionaryEntry.readTsv(dictionaryFile);
    final SearchEvaluator.Report report =
        SearchEvaluator.run(model, passages, dictionary, BITS, SEED, TOP_K);

    final String modelName = modelDirectory.getFileName() + " " + report.dimension() + "d";
    final List<EvalReport> reports = new ArrayList<>();
    reports.add(EvalReport.atLeast("fidelity.recallAt" + TOP_K, DATASET,
        modelName + " turboquant" + BITS, report.fidelityRecallAtK(), MIN_FIDELITY_RECALL));
    reports.add(EvalReport.atLeast("fidelity.rank1Agreement", DATASET,
        modelName + " turboquant" + BITS, report.fidelityAgreement(), MIN_FIDELITY_AGREEMENT));
    for (final SearchEvaluator.RetrievalMetrics metrics : report.halfPassage()) {
      final boolean exact = "exact".equals(metrics.name());
      reports.add(EvalReport.atLeast("halfPassage.recallAt" + TOP_K, DATASET,
          modelName + " " + metrics.name(), metrics.recallAtK(),
          exact ? MIN_HALF_PASSAGE_RECALL : MIN_HALF_PASSAGE_QUANTIZED_RECALL));
      reports.add(EvalReport.atLeast("halfPassage.mrr", DATASET,
          modelName + " " + metrics.name(), metrics.mrr(), 0.0));
    }
    for (final SearchEvaluator.RetrievalMetrics metrics : report.definitionToHeadword()) {
      reports.add(EvalReport.atLeast("definitionToHeadword.mrr", DATASET,
          modelName + " " + metrics.name(), metrics.mrr(), MIN_DEFINITION_MRR));
    }
    reports.add(EvalReport.atLeast("exact.qps", DATASET, modelName,
        report.flat().queriesPerSecond(), 0.0));
    reports.add(EvalReport.atLeast("turboquant.qps", DATASET, modelName,
        report.quantized().queriesPerSecond(), 0.0));
    reports.add(EvalReport.atLeast("passages.indexed", DATASET, modelName,
        report.indexedPassageCount(), 1.0));
    EvalRuns.finish(DATASET, reports);
  }
}
