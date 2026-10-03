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

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import opennlp.tools.chunker.Chunker;
import opennlp.tools.chunker.ChunkerME;
import opennlp.tools.parser.AbstractBottomUpParser;
import opennlp.tools.parser.HeadRules;
import opennlp.tools.parser.Parse;
import opennlp.tools.parser.ParserModel;
import opennlp.tools.parser.ParserType;
import opennlp.tools.postag.POSTagger;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.Parameters;
import opennlp.tools.util.Sequence;
import opennlp.tools.util.Span;
import opennlp.tools.util.TrainingParameters;

public class CustomTaggerParsersTest {

  private static final List<String> TRAINING = List.of(
      "(TOP (S (NP (DT The) (NN dog)) (VP (VBD saw) (NP (DT a) (NN cat))) (. .)))",
      "(TOP (S (NP (DT A) (NN cat)) (VP (VBD saw) (NP (DT the) (NN dog))) (. .)))",
      "(TOP (S (NP (DT The) (NN man)) (VP (VBD ate) (NP (DT an) (NN apple))) (. .)))",
      "(TOP (S (NP (DT The) (NN girl)) (VP (VBD read) (NP (DT a) (NN book))) (. .)))",
      "(TOP (S (NP (NNP Anna)) (VP (VBD walked) (PP (IN to) (NP (DT the) (NN park)))) (. .)))",
      "(TOP (S (NP (NNP Bob)) (VP (VBD ran) (PP (IN to) (NP (DT the) (NN shop)))) (. .)))",
      "(TOP (S (NP (DT The) (NN boy)) (VP (VBD kicked) (NP (DT the) (NN ball))) (. .)))",
      "(TOP (S (NP (DT A) (NN woman)) (VP (VBD bought) (NP (DT a) (NN car))) (. .)))",
      "(TOP (S (NP (NNS Dogs)) (VP (VBP like) (NP (NNS bones))) (. .)))",
      "(TOP (S (NP (NNS Cats)) (VP (VBP chase) (NP (NNS mice))) (. .)))",
      "(TOP (S (NP (DT The) (NN bird)) (VP (VBD sat) (PP (IN on) (NP (DT the) (NN tree)))) (. .)))",
      "(TOP (S (NP (NNP Carl)) (VP (VBD saw) (NP (DT the) (NN bird))) (. .)))",
      "(TOP (S (NP (NP (DT The) (NN dog)) (CC and) (NP (DT the) (NN cat)))"
          + " (VP (VBD slept)) (. .)))",
      "(TOP (S (NP (NNP Bob)) (VP (VBD ran) (ADVP (RB quickly))"
          + " (PP (IN to) (NP (DT the) (NN shop)))) (. .)))");

  private static final String[] TOKENS = {"The", "dog", "saw", "a", "cat", "."};

  private static ParserModel chunkingModel;
  private static ParserModel treeInsertModel;

  @BeforeAll
  static void trainModels() throws IOException {
    final HeadRules rules;
    try (Reader in = new InputStreamReader(
        CustomTaggerParsersTest.class.getResourceAsStream("en_head_rules"), StandardCharsets.UTF_8)) {
      rules = new opennlp.tools.parser.lang.en.HeadRules(in);
    }
    final TrainingParameters params = new TrainingParameters();
    params.put(Parameters.ALGORITHM_PARAM, "MAXENT");
    params.put(Parameters.ITERATIONS_PARAM, 10);
    params.put(Parameters.CUTOFF_PARAM, 0);
    chunkingModel = opennlp.tools.parser.chunking.Parser.train("eng", samples(), rules, params);
    treeInsertModel = opennlp.tools.parser.treeinsert.Parser.train("eng", samples(), rules,
        params);
  }

  /**
   * Training reads the samples more than once and changes the parses it reads, so each
   * pass parses the text again.
   */
  private static ObjectStream<Parse> samples() {
    return new ObjectStream<>() {
      private int next;

      @Override
      public Parse read() {
        return next < TRAINING.size() ? Parse.parseParse(TRAINING.get(next++)) : null;
      }

      @Override
      public void reset() {
        next = 0;
      }
    };
  }

  /** {@return the sentence as the flat token parse a parser takes as input} */
  private static Parse sentence() {
    final String text = String.join(" ", TOKENS);
    final Parse parse = new Parse(text, new Span(0, text.length()),
        AbstractBottomUpParser.INC_NODE, 0, 0);
    int start = 0;
    for (int i = 0; i < TOKENS.length; i++) {
      final int end = start + TOKENS[i].length();
      parse.insert(new Parse(text, new Span(start, end), AbstractBottomUpParser.TOK_NODE, 0, i));
      start = end + 1;
    }
    return parse;
  }

  private static ParserModel model(ParserType type) {
    return type == ParserType.CHUNKING ? chunkingModel : treeInsertModel;
  }

  @ParameterizedTest
  @EnumSource(value = ParserType.class, names = {"CHUNKING", "TREEINSERT"})
  void testSuppliedTaggerDrivesTheParse(ParserType type) {
    final ParserModel model = model(type);
    final CountingTagger tagger = new CountingTagger(new POSTaggerME(model.getParserTaggerModel()));
    final AbstractBottomUpParser parser = CustomTaggerParsers.create(model, tagger);
    final Parse parsed = parser.parse(sentence());
    Assertions.assertNotNull(parsed);
    Assertions.assertTrue(tagger.calls.get() > 0, "the supplied tagger was not used");
  }

  @ParameterizedTest
  @EnumSource(value = ParserType.class, names = {"CHUNKING", "TREEINSERT"})
  void testSuppliedChunkerDrivesTheParse(ParserType type) {
    final ParserModel model = model(type);
    final CountingTagger tagger = new CountingTagger(new POSTaggerME(model.getParserTaggerModel()));
    final CountingChunker chunker =
        new CountingChunker(new ChunkerME(model.getParserChunkerModel()));
    final AbstractBottomUpParser parser = CustomTaggerParsers.create(model, tagger, chunker,
        AbstractBottomUpParser.defaultBeamSize, AbstractBottomUpParser.defaultAdvancePercentage);
    Assertions.assertNotNull(parser.parse(sentence()));
    Assertions.assertTrue(tagger.calls.get() > 0, "the supplied tagger was not used");
    Assertions.assertTrue(chunker.calls.get() > 0, "the supplied chunker was not used");
  }

  @Test
  void testParserTypeFollowsTheModel() {
    final POSTagger tagger = new POSTaggerME(chunkingModel.getParserTaggerModel());
    Assertions.assertInstanceOf(opennlp.tools.parser.chunking.Parser.class,
        CustomTaggerParsers.create(chunkingModel, tagger));
    Assertions.assertInstanceOf(opennlp.tools.parser.treeinsert.Parser.class,
        CustomTaggerParsers.create(treeInsertModel, tagger));
  }

  @Test
  void testNullArgumentsAreRejected() {
    final POSTagger tagger = new POSTaggerME(chunkingModel.getParserTaggerModel());
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CustomTaggerParsers.create(null, tagger));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CustomTaggerParsers.create(chunkingModel, null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> CustomTaggerParsers.create(chunkingModel, tagger, null,
            AbstractBottomUpParser.defaultBeamSize,
            AbstractBottomUpParser.defaultAdvancePercentage));
  }

  /** A tagger that counts the calls the parser makes and delegates them. */
  private static final class CountingTagger implements POSTagger {

    private final POSTagger delegate;
    private final AtomicInteger calls = new AtomicInteger();

    CountingTagger(POSTagger delegate) {
      this.delegate = delegate;
    }

    @Override
    public String[] tag(String[] sentence) {
      calls.incrementAndGet();
      return delegate.tag(sentence);
    }

    @Override
    public String[] tag(String[] sentence, Object[] additionalContext) {
      calls.incrementAndGet();
      return delegate.tag(sentence, additionalContext);
    }

    @Override
    public Sequence[] topKSequences(String[] sentence) {
      calls.incrementAndGet();
      return delegate.topKSequences(sentence);
    }

    @Override
    public Sequence[] topKSequences(String[] sentence, Object[] additionalContext) {
      calls.incrementAndGet();
      return delegate.topKSequences(sentence, additionalContext);
    }
  }

  /** A chunker that counts the calls the parser makes and delegates them. */
  private static final class CountingChunker implements Chunker {

    private final Chunker delegate;
    private final AtomicInteger calls = new AtomicInteger();

    CountingChunker(Chunker delegate) {
      this.delegate = delegate;
    }

    @Override
    public String[] chunk(String[] toks, String[] tags) {
      calls.incrementAndGet();
      return delegate.chunk(toks, tags);
    }

    @Override
    public Span[] chunkAsSpans(String[] toks, String[] tags) {
      calls.incrementAndGet();
      return delegate.chunkAsSpans(toks, tags);
    }

    @Override
    public Sequence[] topKSequences(String[] sentence, String[] tags) {
      calls.incrementAndGet();
      return delegate.topKSequences(sentence, tags);
    }

    @Override
    public Sequence[] topKSequences(String[] sentence, String[] tags, double minSequenceScore) {
      calls.incrementAndGet();
      return delegate.topKSequences(sentence, tags, minSequenceScore);
    }
  }
}
