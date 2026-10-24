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
package opennlp.tools.dehyphenation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.AlignedText;
import opennlp.tools.util.normalizer.TextNormalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the {@link DehyphenationCharSequenceNormalizer} join rule across the recognized
 * hyphens, line breaks, and continuation-line indentation, the no-join cases that must pass
 * through untouched, and the alignment fidelity that lets a consumer report the
 * joined word against the original text.
 */
public class DehyphenationCharSequenceNormalizerTest {

  private static final DehyphenationCharSequenceNormalizer NORMALIZER =
      DehyphenationCharSequenceNormalizer.getInstance();

  /**
   * Deserialization restores the shared normalizer and aligned output.
   *
   * @throws IOException Thrown if serialization fails.
   * @throws ClassNotFoundException Thrown if the normalizer class cannot be loaded.
   */
  @Test
  void testSerializationRestoresSharedInstance() throws IOException, ClassNotFoundException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(NORMALIZER);
    }
    try (ObjectInputStream input = new ObjectInputStream(
        new ByteArrayInputStream(bytes.toByteArray()))) {
      final DehyphenationCharSequenceNormalizer restored =
          (DehyphenationCharSequenceNormalizer) input.readObject();
      assertSame(NORMALIZER, restored);
      assertEquals("litigation", restored.normalize("litiga-\ntion").toString());
      assertEquals(new Span(0, 12),
          restored.normalizeAligned("litiga-\ntion").toOriginalSpan(0, 10));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "litiga-\ntion",       // U+000A line feed
      "litiga-\u000Btion",   // U+000B vertical tab
      "litiga-\ftion",       // U+000C form feed
      "litiga-\rtion",       // U+000D carriage return
      "litiga-\r\ntion",     // U+000D U+000A carriage return + line feed, one break
      "litiga-\u0085tion",   // U+0085 next line
      "litiga-\u2028tion",   // U+2028 line separator
      "litiga-\u2029tion"})  // U+2029 paragraph separator
  void testJoinAcrossEveryLineBreakForm(String text) {
    assertEquals("litigation", NORMALIZER.normalize(text).toString());
  }

  @Test
  void testFormFeedPageBreakDoesNotJoinAcrossThePage() {
    // The form feed after the line break is a second line break (a page break), not
    // continuation-line indentation, so the join must stop: swallowing it would fuse the
    // word with the page header, corrupting "litiga-" into "litigaPage".
    final String text = "litiga-\n\fPage 3\ntion";
    assertEquals(text, NORMALIZER.normalize(text).toString());
  }

  @Test
  void testJoinAcrossSoftHyphen() {
    assertEquals("litigation", NORMALIZER.normalize("litiga\u00AD\ntion").toString());
  }

  @Test
  void testJoinAcrossTypesetHyphen() {
    // PDF extractors such as Poppler and PDFBox emit U+2010 HYPHEN for typeset hyphens.
    assertEquals("wellknown", NORMALIZER.normalize("well\u2010\nknown").toString());
  }

  /**
   * Joins at the hyphens that Armenian, Canadian syllabics, Fraktur and Yezidi text use to
   * hyphenate a word at the end of a line.
   *
   * @param text The hyphenated text.
   * @param joined The joined word.
   */
  @ParameterizedTest
  @CsvSource({
      // U+058A ARMENIAN HYPHEN
      "'\u0540\u0561\u0575\u0561\u057D\u058A\n\u057F\u0561\u0576', "
          + "'\u0540\u0561\u0575\u0561\u057D\u057F\u0561\u0576'",
      // U+1400 CANADIAN SYLLABICS HYPHEN
      "'\u1403\u14C4\u1400\n\u1483\u1450\u1450\u1466', '\u1403\u14C4\u1483\u1450\u1450\u1466'",
      // U+2E17 DOUBLE OBLIQUE HYPHEN, the hyphen of Fraktur
      "'Gesell\u2E17\nschaft', 'Gesellschaft'",
      // U+10EAD YEZIDI HYPHENATION MARK, a surrogate pair like the Yezidi letters
      "'\uD803\uDE80\uD803\uDE81\uD803\uDEAD\n\uD803\uDE82\uD803\uDE83', "
          + "'\uD803\uDE80\uD803\uDE81\uD803\uDE82\uD803\uDE83'"})
  void testJoinAcrossHyphenOfOtherScripts(String text, String joined) {
    final AlignedText aligned = NORMALIZER.normalizeAligned(text);
    assertEquals(joined, NORMALIZER.normalize(text).toString());
    assertEquals(joined, aligned.normalizedString());
    assertEquals(new Span(0, text.length()), aligned.toOriginalSpan(0, joined.length()));
  }

  /**
   * Leaves dashes and the hyphens that do not mark a hyphenation break untouched.
   *
   * @param hyphen The code point before the line break.
   */
  @ParameterizedTest
  @ValueSource(ints = {
      0x05BE,    // HEBREW PUNCTUATION MAQAF joins the words of a compound
      0x1806,    // MONGOLIAN TODO SOFT HYPHEN is written at the start of the next line
      0x2011,    // NON-BREAKING HYPHEN
      0x2012,    // FIGURE DASH
      0x2013,    // EN DASH
      0x2014,    // EM DASH
      0x2E40,    // DOUBLE HYPHEN
      0x30A0,    // KATAKANA-HIRAGANA DOUBLE HYPHEN joins the words of a name
      0xFE63,    // SMALL HYPHEN-MINUS
      0xFF0D,    // FULLWIDTH HYPHEN-MINUS
      0x2212})   // MINUS SIGN
  void testDashesAndJoiningHyphensAreLeftAlone(int hyphen) {
    final String text = "litiga" + Character.toString(hyphen) + "\ntion";
    assertSame(text, NORMALIZER.normalize(text));
  }

  @Test
  void testNonBreakingHyphenIsLeftAlone() {
    // U+2011 NON-BREAKING HYPHEN asserts the word was NOT broken at this hyphen, so a
    // line break after it is not a hyphenation break and must not join.
    assertEquals("well\u2011\nknown", NORMALIZER.normalize("well\u2011\nknown").toString());
  }

  @Test
  void testHyphenatedCompoundSplitAtItsBreakJoinsAsDocumented() {
    // The documented dictionary-free trade-off: a genuine compound split at its own hyphen
    // joins too, "well-\nknown" to "wellknown".
    assertEquals("wellknown", NORMALIZER.normalize("well-\nknown").toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"com-\r\n  plete", "com-\n\t\tplete", "com-\n \u00A0plete"})
  void testJoinConsumesContinuationLineIndentation(String text) {
    assertEquals("complete", NORMALIZER.normalize(text).toString());
  }

  /**
   * Joins when PDF or OCR output leaves spaces between the hyphen and the line break.
   *
   * @param text The hyphenated text with trailing whitespace on the first line.
   */
  @ParameterizedTest
  @ValueSource(strings = {"litiga- \ntion", "litiga-\t\r\ntion", "litiga-\u00A0 \n  tion",
      "litiga-\u3000\u2028tion", "litiga\u00AD \ntion"})
  void testJoinIgnoresWhitespaceBeforeTheBreak(String text) {
    final AlignedText aligned = NORMALIZER.normalizeAligned(text);
    assertEquals("litigation", NORMALIZER.normalize(text).toString());
    assertEquals("litigation", aligned.normalizedString());
    assertEquals(new Span(0, text.length()), aligned.toOriginalSpan(0, 10));
  }

  /**
   * Whitespace before the hyphen, or a hyphen followed by spaces and no line break, is not
   * a hyphenation break.
   *
   * @param text The text to leave unchanged.
   */
  @ParameterizedTest
  @ValueSource(strings = {"litiga -\ntion", "litiga- tion", "litiga-  ", "litiga- \n\ntion",
      "litiga- \n \ntion"})
  void testWhitespaceWithoutASingleBreakIsLeftAlone(String text) {
    assertSame(text, NORMALIZER.normalize(text));
  }

  /**
   * A capitalized word after the break starts the second part of a compound, so the hyphen
   * stays.
   *
   * @param text The text to leave unchanged.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Anglo-\nAmerican", "non-\nEnglish", "e-\nMail", "Vitamin-\nC",
      "NATO-\nGipfel", "non-\nE\u0301tude", "x-\n\u01C5ab",
      "\u0421\u0430\u043D\u043A\u0442-\n\u041F\u0435\u0442\u0435\u0440\u0431\u0443\u0440\u0433"})
  void testCapitalizedContinuationKeepsTheHyphen(String text) {
    assertSame(text, NORMALIZER.normalize(text));
  }

  /**
   * Text in capitals joins, and a soft hyphen always marks a break inside one word.
   *
   * @param text The hyphenated text.
   * @param joined The joined word.
   */
  @ParameterizedTest
  @CsvSource({
      "'LITIGA-\nTION', 'LITIGATION'",
      "'\u041F\u0415\u0422\u0415\u0420-\n\u0411\u0423\u0420\u0413', "
          + "'\u041F\u0415\u0422\u0415\u0420\u0411\u0423\u0420\u0413'",
      "'Mc\u00AD\nDonald', 'McDonald'"})
  void testCapitalsJoin(String text, String joined) {
    assertEquals(joined, NORMALIZER.normalize(text).toString());
  }

  /**
   * Han, Hiragana, Katakana and Hangul text breaks lines between any two characters without
   * a hyphen, so a hyphen at a line end next to these scripts is part of the text.
   *
   * @param text The text to leave unchanged.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "\u6771\u4EAC-\n\u5927\u962A",                    // Han on both sides
      "\u3072\u3089-\n\u304C\u306A",                    // Hiragana
      "\u30B3\u30F3\u30D4\u30E5\u30FC-\n\u30BF\u30FC",  // Katakana after the sound mark
      "\uD55C\uAD6D-\n\uC5B4",                          // Hangul
      "\uD840\uDC00-\n\uD840\uDC01",                    // Han, supplementary plane
      "abc-\n\u6771\u4EAC", "\u6771\u4EAC-\nabc"})
  void testScriptsWithoutHyphenationAreLeftAlone(String text) {
    assertSame(text, NORMALIZER.normalize(text));
  }

  @Test
  void testMultipleJoinsInOneText() {
    assertEquals("litigation complete",
        NORMALIZER.normalize("litiga-\ntion com-\nplete").toString());
  }

  @Test
  void testInlineHyphenIsLeftAlone() {
    // No line break follows the hyphen, so the compound passes through.
    assertEquals("well-known", NORMALIZER.normalize("well-known").toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ver-\n3", "end-\n."})
  void testHyphenBreakBeforeNonLetterIsLeftAlone(String text) {
    // A break after the hyphen is not enough; the continuation must start with a letter.
    assertEquals(text, NORMALIZER.normalize(text).toString());
  }

  @Test
  void testHyphenAtEndOfTextIsLeftAlone() {
    assertEquals("word-", NORMALIZER.normalize("word-").toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"-\nword", "\u0301-\nword", "3\u0301-\nword", "\u216B-\nword"})
  void testHyphenWithoutPrecedingLetterIsLeftAlone(String text) {
    // A hyphen at the start, after a lone combining mark, after a digit with a mark, or after
    // the letter number U+216B ROMAN NUMERAL TWELVE has no letter before it.
    assertEquals(text, NORMALIZER.normalize(text).toString());
  }

  @Test
  void testJoinAfterLetterWithCombiningMarks() {
    // Decomposed text (NFD), as macOS PDF extractors emit it, ends "cafe" with U+0301 COMBINING
    // ACUTE ACCENT. A mark is not a letter, so the letter check must look past the marks to the
    // "e" that carries them.
    final String text = "cafe\u0301-\nteria";
    assertEquals("cafe\u0301teria", NORMALIZER.normalize(text).toString());
    final AlignedText aligned = NORMALIZER.normalizeAligned(text);
    assertEquals("cafe\u0301teria", aligned.normalizedString());
    assertEquals(new Span(0, 12), aligned.toOriginalSpan(0, 10));
    // Two stacked marks and an enclosing mark (U+20DD) are looked past as well.
    assertEquals("a\u0308\u0301b", NORMALIZER.normalize("a\u0308\u0301-\nb").toString());
    assertEquals("a\u20DDb", NORMALIZER.normalize("a\u20DD-\nb").toString());
  }

  @Test
  void testLineBreakWithoutHyphenIsLeftAlone() {
    assertEquals("word\nnext", NORMALIZER.normalize("word\nnext").toString());
  }

  @Test
  void testNoOpReturnsSameInstanceAndIdentityAlignment() {
    final String text = "an ordinary sentence.";
    assertSame(text, NORMALIZER.normalize(text));

    final AlignedText aligned = NORMALIZER.normalizeAligned(text);
    assertEquals(text, aligned.normalizedString());
    assertEquals(new Span(0, text.length()), aligned.toOriginalSpan(0, text.length()));
    assertEquals(new Span(3, 11), aligned.toOriginalSpan(3, 11));
    assertEquals(new Span(3, 11), aligned.toNormalizedSpan(3, 11));
  }

  @Test
  void testAlignedNormalizationMatchesPlainNormalization() {
    final String text = "litiga-\ntion com-\r\n  plete";
    assertEquals(NORMALIZER.normalize(text).toString(),
        NORMALIZER.normalizeAligned(text).normalizedString());
  }

  @Test
  void testJoinedWordSpanCoversBothHalvesAndTheBreakInTheOriginal() {
    // Original: "litiga-\ntion" is 12 code units; the joined "litigation" is 10.
    final AlignedText aligned = NORMALIZER.normalizeAligned("litiga-\ntion");
    assertEquals("litigation", aligned.normalizedString());
    assertEquals(new Span(0, 12), aligned.toOriginalSpan(0, 10));
    // The deleted hyphen maps forward to an empty span at the join point.
    assertEquals(new Span(6, 6), aligned.toNormalizedSpan(6, 7));
  }

  @Test
  void testSpansAroundTheEditMapUnchanged() {
    final String text = "a litiga-\ntion b";
    final AlignedText aligned = NORMALIZER.normalizeAligned(text);
    assertEquals("a litigation b", aligned.normalizedString());
    assertEquals(new Span(0, 1), aligned.toOriginalSpan(0, 1));
    assertEquals(new Span(2, 14), aligned.toOriginalSpan(2, 12));
    assertEquals(new Span(15, 16), aligned.toOriginalSpan(13, 14));
  }

  @Test
  void testCarriageReturnLineFeedWithIndentationAlignment() {
    // Original: "com-\r\n  plete" is 13 code units; the joined "complete" is 8.
    final AlignedText aligned = NORMALIZER.normalizeAligned("com-\r\n  plete");
    assertEquals("complete", aligned.normalizedString());
    assertEquals(new Span(0, 13), aligned.toOriginalSpan(0, 8));
    assertEquals(new Span(3, 3), aligned.toNormalizedSpan(3, 4));
  }

  @Test
  void testJoinAfterSupplementaryPlaneLetter() {
    // U+10400 DESERET CAPITAL LETTER LONG I is a supplementary-plane letter (a surrogate
    // pair in UTF-16), so the codePointBefore path must recognize it.
    final String text = "\uD801\uDC00-\nbc";
    final AlignedText aligned = NORMALIZER.normalizeAligned(text);
    assertEquals("\uD801\uDC00bc", aligned.normalizedString());
    assertEquals(new Span(0, 6), aligned.toOriginalSpan(0, 4));
  }

  @Test
  void testSupplementaryCharactersKeepCodeUnitOffsets() {
    // The emoji after the join occupies two UTF-16 code units; the trailing letter's
    // original span must still be reported in code units.
    final String text = "a-\nb😀c";
    final AlignedText aligned = NORMALIZER.normalizeAligned(text);
    assertEquals("ab😀c", aligned.normalizedString());
    assertEquals(new Span(6, 7), aligned.toOriginalSpan(4, 5));
    assertEquals(new Span(0, 6), aligned.toOriginalSpan(0, 4));
  }

  @Test
  void testNullIsRejected() {
    assertEquals("text must not be null", assertThrows(IllegalArgumentException.class,
        () -> NORMALIZER.normalize(null)).getMessage());
    assertEquals("text must not be null", assertThrows(IllegalArgumentException.class,
        () -> NORMALIZER.normalizeAligned(null)).getMessage());
  }

  /** Text without a join comes back as the supplied sequence, whatever its type. */
  @Test
  void testNoOpReturnsTheSuppliedSequence() {
    final StringBuilder builder = new StringBuilder("word\nnext");
    assertSame(builder, NORMALIZER.normalize(builder));
    final CharBuffer buffer = CharBuffer.wrap("x well-known y");
    buffer.position(2).limit(12);
    assertSame(buffer, NORMALIZER.normalize(buffer));
    assertEquals(2, buffer.position());
    assertEquals("litigation", NORMALIZER.normalize(new StringBuilder("litiga-\ntion")).toString());
  }

  /**
   * The de-hyphenation step must come before the steps that change line breaks or hyphens.
   */
  @Test
  void testPipelineOrder() {
    final String text = "litiga-\ntion well\u2011\nknown";
    assertEquals("litigation well\u2011 known", TextNormalizer.builder().with(NORMALIZER)
        .whitespace().build().normalize(text).toString());
    assertEquals("litiga- tion well\u2011 known", TextNormalizer.builder().whitespace()
        .with(NORMALIZER).build().normalize(text).toString());
    assertEquals("litigation wellknown", TextNormalizer.builder().dashes()
        .with(NORMALIZER).build().normalize(text).toString());
  }

  /**
   * The shared instance gives the serial results from several threads.
   *
   * @throws InterruptedException Thrown if the test is interrupted.
   * @throws ExecutionException Thrown if a normalization task fails.
   */
  @Test
  void testConcurrentCallsMatchSerialResults() throws InterruptedException, ExecutionException {
    final List<String> texts = List.of("litiga-\ntion", "Anglo-\nAmerican", "com-\r\n  plete",
        "\u6771\u4EAC-\n\u5927\u962A", "Gesell\u2E17\nschaft");
    final List<String> expected = new ArrayList<>();
    for (final String text : texts) {
      expected.add(NORMALIZER.normalize(text).toString());
    }
    final List<Callable<String>> tasks = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      final String text = texts.get(i % texts.size());
      tasks.add(() -> NORMALIZER.normalizeAligned(new StringBuilder(text)).normalizedString());
    }
    try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
      final List<Future<String>> results = executor.invokeAll(tasks);
      for (int i = 0; i < results.size(); i++) {
        assertEquals(expected.get(i % texts.size()), results.get(i).get());
      }
    }
  }

  /** Retains empty-input coverage from core's shared normalizer contract suite. */
  @Test
  void testEmptyInputContract() {
    assertEquals("", NORMALIZER.normalize("").toString());
    assertEquals("", NORMALIZER.normalizeAligned("").normalizedString());
  }

}
