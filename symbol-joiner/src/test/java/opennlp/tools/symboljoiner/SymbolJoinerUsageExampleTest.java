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
package opennlp.tools.symboljoiner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import opennlp.tools.tokenize.WhitespaceTokenizer;
import opennlp.tools.tokenize.uax29.WordTokenizer;
import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.CharSequenceNormalizer;
import opennlp.tools.util.normalizer.TextNormalizer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests per-token usage with the normalizer builder and source spans. */
class SymbolJoinerUsageExampleTest {

  private static final String SOURCE = "Dungeons & Dragons";
  private static final SymbolJoinerCharSequenceNormalizer SYMBOLS =
      SymbolJoinerCharSequenceNormalizer.getInstance();

  /** Normalizes token forms without changing their original character spans. */
  @Test
  void testManualExample() {
    String text = SOURCE;
    CharSequenceNormalizer normalizer = TextNormalizer.builder()
        .with(SymbolJoinerCharSequenceNormalizer.getInstance())
        .caseFold()
        .build();
    Span[] spans = WhitespaceTokenizer.INSTANCE.tokenizePos(text);
    String[] forms = new String[spans.length];
    for (int i = 0; i < spans.length; i++) {
      forms[i] = normalizer.normalize(spans[i].getCoveredText(text)).toString();
    }

    assertArrayEquals(new String[] {"dungeons", "and", "dragons"}, forms);
    assertArrayEquals(new Span[] {new Span(0, 8), new Span(9, 10), new Span(11, 18)}, spans);
    assertEquals("&", spans[1].getCoveredText(text).toString());
  }

  /** Passing a complete phrase does not normalize individual symbol tokens. */
  @Test
  void testDoesNotTokenizePhrase() {
    assertSame(SOURCE, SYMBOLS.normalize(SOURCE));
  }

  /** The default word tokenizer removes the ampersand before normalization can use it. */
  @Test
  void testWordTokenizerOmitsAmpersand() {
    assertArrayEquals(new String[] {"Dungeons", "Dragons"}, new WordTokenizer().tokenize(SOURCE));
  }

  /** Trimming must precede symbol expansion if the supplied token includes whitespace. */
  @Test
  void testPipelineOrder() {
    assertEquals("and", TextNormalizer.builder().whitespace().with(SYMBOLS).build()
        .normalize(" & ").toString());
    assertEquals("&", TextNormalizer.builder().with(SYMBOLS).whitespace().build()
        .normalize(" & ").toString());
  }

  /** NFKC folds the trademark sign to letters, so the joiner must run before it. */
  @Test
  void testJoinerRunsBeforeNfkc() {
    assertEquals("trademark", TextNormalizer.builder().with(SYMBOLS).nfkc().build()
        .normalize("\u2122").toString());
    assertEquals("TM", TextNormalizer.builder().nfkc().with(SYMBOLS).build()
        .normalize("\u2122").toString());
  }

  /**
   * A serialized pipeline keeps the joiner.
   *
   * @throws IOException Thrown if serialization fails.
   * @throws ClassNotFoundException Thrown if a serialized type cannot be loaded.
   */
  @Test
  void testSerializedPipelineKeepsJoiner() throws IOException, ClassNotFoundException {
    final CharSequenceNormalizer pipeline = TextNormalizer.builder().with(SYMBOLS).caseFold().build();
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(pipeline);
    }
    try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      final CharSequenceNormalizer restored = (CharSequenceNormalizer) input.readObject();
      assertEquals("and", restored.normalize("\uFF06").toString());
      assertEquals("r&d", restored.normalize("R&D").toString());
    }
  }

  /** The symbol normalizer does not supply character-level alignment. */
  @Test
  void testAlignedPipelineRejectsSymbolNormalizer() {
    final IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> TextNormalizer.builder().with(SYMBOLS).buildAligned());
    assertTrue(error.getMessage().contains(SymbolJoinerCharSequenceNormalizer.class.getName()));
  }

  /**
   * Shares the normalizer across concurrent requests with separate input buffers.
   *
   * @throws InterruptedException Thrown if the test is interrupted.
   * @throws ExecutionException Thrown if a normalization task fails.
   */
  @Test
  void testConcurrentCalls() throws InterruptedException, ExecutionException {
    final List<Callable<String>> tasks = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      final String text = i % 2 == 0 ? "&" : "R&D";
      tasks.add(() -> SYMBOLS.normalize(new StringBuilder(text)).toString());
    }
    try (var executor = Executors.newFixedThreadPool(4)) {
      final var results = executor.invokeAll(tasks);
      for (int i = 0; i < results.size(); i++) {
        assertEquals(i % 2 == 0 ? "and" : "R&D", results.get(i).get());
      }
    }
  }
}
