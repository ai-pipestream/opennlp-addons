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
package opennlp.addons.eval.coref;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import opennlp.addons.eval.EvalReport;
import opennlp.addons.eval.EvalRuns;
import opennlp.addons.eval.coref.CorefEvalModels.Mention;
import opennlp.tools.coref.CorefAnnotator;
import opennlp.tools.coref.CorefScorer;
import opennlp.tools.coref.CorefScores;
import opennlp.tools.coref.formats.ConlluCorefDocumentStream;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.Layers;
import opennlp.tools.formats.conllu.ConlluTagset;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Scores the rule-based {@link CorefAnnotator} on OntoGUM, or any corpus in the same CoNLL-U
 * coreference encoding such as CorefUD, with the CoNLL metrics of {@link CorefScorer}.
 *
 * <p>Data: {@code <eval root>/coref/ontogum/} holds the {@code .conllu} files of one split,
 * for example the test documents of {@code coref/ontogum/conllu} in a GUM checkout
 * (annotations CC BY 4.0, texts under their own licenses). Models: the three
 * {@code en-ner-*.bin} finders and {@code en-chunker.bin} in the model directory. Sentences,
 * tokens and Penn tags are gold, read by {@link ConlluCorefDocumentStream}; entities and
 * chunks are predicted. Singleton entities are dropped from key and response, as the
 * CoNLL-2012 scorer does.</p>
 *
 * <p>The coref chapter reports a CoNLL average of 46.8 for this configuration on the OntoGUM
 * test split with the corpus speaker lines; the threshold is a regression floor well below
 * it, and the TSV report is the measurement. The training and sweep options of the
 * coref-formats eval test are not repeated here.</p>
 */
class OntoGumEvalTest {

  static final String DATASET = "ontogum";
  static final String DIRECTORY = "coref";
  static final String SUBDIRECTORY = "ontogum";
  static final String CONLLU_SUFFIX = ".conllu";

  /** The CoNLL average floor; the measured value is around 0.47 on the test split. */
  static final double MIN_CONLL = 0.30;

  @Test
  void testRuleBasedConllAverage() throws IOException {
    final Path directory = EvalRuns.assumeDirectory(
        EvalRuns.DATA.dataset(DIRECTORY).resolve(SUBDIRECTORY));
    final List<Path> files = new ArrayList<>();
    try (Stream<Path> listing = Files.list(directory)) {
      listing.filter(f -> f.getFileName().toString().endsWith(CONLLU_SUFFIX)).sorted()
          .forEach(files::add);
    }
    assumeTrue(!files.isEmpty(), "skipped: no " + CONLLU_SUFFIX + " files in " + directory);
    EvalRuns.assumeModels(CorefEvalModels.NER_MODELS);
    EvalRuns.assumeModels(CorefEvalModels.CHUNKER_MODEL);

    final DocumentAnnotator entities = CorefEvalModels.entityAnnotator();
    final DocumentAnnotator chunker = CorefEvalModels.chunkerAnnotator();
    final CorefAnnotator annotator = new CorefAnnotator();
    final CorefScorer scorer = new CorefScorer();
    int documents = 0;
    long corefNanos = 0;
    for (final Path file : files) {
      try (ConlluCorefDocumentStream stream = new ConlluCorefDocumentStream(
          () -> Files.newInputStream(file), ConlluTagset.X)) {
        Document gold;
        while ((gold = stream.read()) != null) {
          final List<Set<Mention>> key =
              CorefEvalModels.partition(gold.get(CorefAnnotator.GOLD_CHAINS));
          Document input = Document.of(gold.text())
              .with(Layers.SENTENCES, gold.get(Layers.SENTENCES))
              .with(Layers.TOKENS, gold.get(Layers.TOKENS))
              .with(Layers.POS_TAGS, gold.get(Layers.POS_TAGS));
          if (gold.layers().contains(CorefAnnotator.SPEAKERS)) {
            input = input.with(CorefAnnotator.SPEAKERS, gold.get(CorefAnnotator.SPEAKERS));
          }
          input = chunker.annotate(entities.annotate(input));
          final long started = System.nanoTime();
          final Document output = annotator.annotate(input);
          corefNanos += System.nanoTime() - started;
          scorer.add(key, CorefEvalModels.partition(output.get(CorefAnnotator.CHAINS)));
          documents++;
        }
      }
    }
    final CorefScores scores = scorer.scores();
    final String model = "rules + en-ner + en-chunker";
    EvalRuns.finish(DATASET, List.of(
        EvalReport.atLeast("conll", DATASET, model, scores.conll(), MIN_CONLL),
        EvalReport.atLeast("muc.f1", DATASET, model, scores.muc().f1(), 0.0),
        EvalReport.atLeast("bcubed.f1", DATASET, model, scores.bCubed().f1(), 0.0),
        EvalReport.atLeast("ceafe.f1", DATASET, model, scores.ceafE().f1(), 0.0),
        EvalReport.atLeast("mentions.f1", DATASET, model, scores.mentions().f1(), 0.0),
        EvalReport.atLeast("documents", DATASET, model, documents, 1.0),
        EvalReport.atLeast("documents.per.second", DATASET, model,
            documents / Math.max(corefNanos / 1e9, 1e-9), 0.0)));
  }
}
