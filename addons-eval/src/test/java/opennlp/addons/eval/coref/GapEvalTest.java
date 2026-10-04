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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import opennlp.addons.eval.EvalReport;
import opennlp.addons.eval.EvalRuns;
import opennlp.tools.coref.CorefAnnotator;
import opennlp.tools.coref.CorefMention;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTagFormat;
import opennlp.tools.postag.POSTaggerAnnotator;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetectorAnnotator;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerAnnotator;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scores pronoun resolution of the rule-based {@link CorefAnnotator} on GAP (Webster et al.,
 * TACL 2018) with every layer predicted by core's document annotators.
 *
 * <p>Data: {@code <eval root>/coref/gap/gap-development.tsv}, the development split of the
 * GAP release (Apache License 2.0): one snippet per row with a pronoun and two candidate
 * names, each with a coreference label. Models in the model directory: the
 * {@code opennlp-en-ud-ewt} sentence and token models of the 2.5.4 release,
 * {@code en-pos-maxent.bin} tagging in Penn format, the three {@code en-ner-*.bin} finders
 * and {@code en-chunker.bin}. A name counts as resolved when a non-pronoun mention of the
 * pronoun's chain overlaps its span.</p>
 *
 * <p>Reported: the F1 over both labels of every snippet, the F1 by the pronoun's gender and
 * their ratio, the paper's bias measure. The coref chapter reports 46.1 development F1 for the
 * CC BY ranker; the threshold is a regression floor for the rule-based resolver, and the TSV
 * report is the measurement.</p>
 */
class GapEvalTest {

  static final String DATASET = "gap";
  static final String DIRECTORY = "coref";
  static final String FILE = "gap-development.tsv";
  static final String SENTENCE_MODEL = "opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin";
  static final String TOKEN_MODEL = "opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin";
  static final String POS_MODEL = "en-pos-maxent.bin";
  static final Set<String> MASCULINE = Set.of("he", "his", "him");

  /** The F1 floor; the chapter's ranker figure is 0.461 on this split. */
  static final double MIN_F1 = 0.30;

  /** The number of tab-separated columns a GAP row has, including the URL. */
  static final int COLUMNS = 11;

  /** True positive, false positive and false negative counts of one group. */
  static final class Counts {
    int tp;
    int fp;
    int fn;

    void add(boolean gold, boolean predicted) {
      if (gold && predicted) {
        tp++;
      } else if (predicted) {
        fp++;
      } else if (gold) {
        fn++;
      }
    }

    double f1() {
      final double p = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
      final double r = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
      return p + r == 0 ? 0 : 2 * p * r / (p + r);
    }
  }

  @Test
  void testRuleBasedPronounResolution() throws IOException {
    final Path file = EvalRuns.assumeFile(
        EvalRuns.DATA.dataset(DIRECTORY).resolve(DATASET).resolve(FILE));
    EvalRuns.assumeModels(SENTENCE_MODEL, TOKEN_MODEL, POS_MODEL);
    EvalRuns.assumeModels(CorefEvalModels.NER_MODELS);
    EvalRuns.assumeModels(CorefEvalModels.CHUNKER_MODEL);

    final DocumentAnnotator sentences = new SentenceDetectorAnnotator(new SentenceDetectorME(
        new SentenceModel(EvalRuns.DATA.model(SENTENCE_MODEL))));
    final DocumentAnnotator tokens = new TokenizerAnnotator(new TokenizerME(
        new TokenizerModel(EvalRuns.DATA.model(TOKEN_MODEL))));
    // The chunker and the mention detector read Penn tags; the tagger converts to UD by default.
    final DocumentAnnotator tags = new POSTaggerAnnotator(new POSTaggerME(
        new POSModel(EvalRuns.DATA.model(POS_MODEL)), POSTagFormat.PENN));
    final DocumentAnnotator entities = CorefEvalModels.entityAnnotator();
    final DocumentAnnotator chunker = CorefEvalModels.chunkerAnnotator();
    final CorefAnnotator annotator = new CorefAnnotator();

    final Counts all = new Counts();
    final Counts masculine = new Counts();
    final Counts feminine = new Counts();
    int snippets = 0;
    for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      final String[] fields = StringUtil.split(line, '\t');
      if (fields.length < COLUMNS - 1 || "ID".equals(fields[0])) {
        continue;
      }
      final String text = fields[1];
      final Span pronoun = span(fields[2], fields[3]);
      final Span a = span(fields[4], fields[5]);
      final Span b = span(fields[7], fields[8]);
      final Document resolved = annotator.annotate(chunker.annotate(entities.annotate(
          tags.annotate(tokens.annotate(sentences.annotate(Document.of(text)))))));
      final List<Annotation<CorefMention>> chains = resolved.get(CorefAnnotator.CHAINS);
      int chain = -1;
      for (final Annotation<CorefMention> mention : chains) {
        if (mention.span().getStart() == pronoun.getStart()
            && mention.span().getEnd() == pronoun.getEnd()) {
          chain = mention.value().chain();
        }
      }
      final Counts gender = MASCULINE.contains(StringUtil.toLowerCase(fields[2]))
          ? masculine : feminine;
      for (final Counts counts : new Counts[] {all, gender}) {
        counts.add(Boolean.parseBoolean(fields[6]), linked(chains, chain, a));
        counts.add(Boolean.parseBoolean(fields[9]), linked(chains, chain, b));
      }
      snippets++;
    }
    assertTrue(snippets > 0, "no GAP rows in " + file);
    final double bias = masculine.f1() == 0 ? 0 : feminine.f1() / masculine.f1();
    final String model = "rules + en-ud-ewt + en-pos-maxent + en-ner + en-chunker";
    EvalRuns.finish(DATASET, List.of(
        EvalReport.atLeast("f1", DATASET, model, all.f1(), MIN_F1),
        EvalReport.atLeast("f1.masculine", DATASET, model, masculine.f1(), 0.0),
        EvalReport.atLeast("f1.feminine", DATASET, model, feminine.f1(), 0.0),
        EvalReport.atLeast("bias.feminine.over.masculine", DATASET, model, bias, 0.0),
        EvalReport.atLeast("snippets", DATASET, model, snippets, 1.0)));
  }

  /** {@return the character span of a GAP field from its text and offset columns} */
  private static Span span(String text, String offset) {
    final int start = Integer.parseInt(offset);
    return new Span(start, start + text.length());
  }

  /** {@return whether a non-pronoun mention of the chain overlaps the name} */
  private static boolean linked(List<Annotation<CorefMention>> chains, int chain, Span name) {
    if (chain < 0) {
      return false;
    }
    for (final Annotation<CorefMention> mention : chains) {
      if (mention.value().chain() == chain && mention.span().intersects(name)
          && !CorefMention.KIND_PRONOUN.equals(mention.value().kind())) {
        return true;
      }
    }
    return false;
  }
}
