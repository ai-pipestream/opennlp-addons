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
package opennlp.addons.eval.postag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import opennlp.addons.eval.EvalReport;
import opennlp.addons.eval.EvalRuns;
import opennlp.tools.formats.conllu.ConlluPOSSampleStream;
import opennlp.tools.formats.conllu.ConlluStream;
import opennlp.tools.formats.conllu.ConlluTagset;
import opennlp.tools.postag.POSEvaluator;
import opennlp.tools.postag.POSSample;
import opennlp.tools.postag.neural.BilstmPOSModel;
import opennlp.tools.postag.neural.BilstmPOSTagger;
import opennlp.tools.postag.neural.BilstmPOSTrainer;
import opennlp.tools.util.MarkableFileInputStreamFactory;
import opennlp.tools.util.ObjectStream;

/**
 * Trains the BiLSTM tagger of neural-postag on a Universal Dependencies treebank and scores
 * its UPOS word accuracy on the treebank's test split with core's {@link POSEvaluator}.
 *
 * <p>Data: {@code <eval root>/ud/train.conllu} and {@code test.conllu}, a UD treebank's
 * splits renamed or linked (for example {@code en_ewt-ud-train.conllu} and
 * {@code en_ewt-ud-test.conllu}). The samples come from core's {@link ConlluStream}, which
 * merges a multiword token into its surface form; the neural-postag eval test reads the
 * syntactic words instead, so the two figures differ slightly on treebanks with contractions.
 * Moving that word-based stream into neural-postag's main code is listed as a follow-up in
 * the README.</p>
 *
 * <p>Training uses {@link BilstmPOSTrainer.Settings#defaults()} and takes minutes on a
 * laptop. The threshold is the regression floor of the neural-postag eval test; the TSV
 * report carries the measured accuracy.</p>
 */
class BilstmPosEvalTest {

  static final String DATASET = "ud";
  static final String TRAIN_FILE = "train.conllu";
  static final String TEST_FILE = "test.conllu";

  /** Far below any plausible UPOS result; it catches a broken tagger, not a weaker one. */
  static final double MIN_ACCURACY = 0.85;

  @Test
  void testBilstmUposAccuracy() throws IOException {
    final Path dataset = EvalRuns.assumeDirectory(EvalRuns.DATA.dataset(DATASET));
    final Path trainFile = EvalRuns.assumeFile(dataset.resolve(TRAIN_FILE));
    final Path testFile = EvalRuns.assumeFile(dataset.resolve(TEST_FILE));

    final BilstmPOSTrainer.Settings settings = BilstmPOSTrainer.Settings.defaults();
    final long trainStart = System.nanoTime();
    final BilstmPOSModel model;
    try (ObjectStream<POSSample> train = samples(trainFile)) {
      model = BilstmPOSTrainer.train(train, settings);
    }
    final double trainSeconds = (System.nanoTime() - trainStart) / 1e9;

    final POSEvaluator evaluator = new POSEvaluator(new BilstmPOSTagger(model));
    try (ObjectStream<POSSample> test = samples(testFile)) {
      evaluator.evaluate(test);
    }
    final String modelName = "bilstm h" + settings.hiddenSize() + " e" + settings.epochs()
        + " seed" + settings.seed();
    EvalRuns.finish(DATASET, List.of(
        EvalReport.atLeast("upos.accuracy", DATASET, modelName,
            evaluator.getWordAccuracy(), MIN_ACCURACY),
        EvalReport.atLeast("test.words", DATASET, modelName, evaluator.getWordCount(), 1.0),
        EvalReport.atLeast("train.seconds", DATASET, modelName, trainSeconds, 0.0)));
  }

  /**
   * Opens a CoNLL-U file as UPOS samples.
   *
   * @param conllu The CoNLL-U file.
   * @return The stream; the caller closes it.
   * @throws IOException Thrown if the file cannot be opened.
   */
  private static ObjectStream<POSSample> samples(Path conllu) throws IOException {
    return new ConlluPOSSampleStream(
        new ConlluStream(new MarkableFileInputStreamFactory(conllu.toFile())), ConlluTagset.U);
  }
}
