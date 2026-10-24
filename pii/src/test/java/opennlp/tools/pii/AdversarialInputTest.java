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

package opennlp.tools.pii;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins how the structured detectors treat digits outside ASCII, invisible characters,
 * lone surrogates and very long inputs.
 */
class AdversarialInputTest {

  /**
   * A text that the structured detectors report a value in.
   *
   * @param prefix The text before the value.
   * @param value The value, which ends the text.
   * @param type The type reported for the value.
   * @param labeled Whether the prefix ends in a label the detector requires.
   */
  private record Sample(String prefix, String value, String type, boolean labeled) {

    String text() {
      return prefix + value;
    }

    @Override
    public String toString() {
      return text();
    }
  }

  private static final List<Sample> SAMPLES = List.of(
      new Sample("card ", "4111 1111 1111 1111", PiiMention.TYPE_CARD, false),
      new Sample("tel ", "+1 415 555 0123", PiiMention.TYPE_PHONE, false),
      new Sample("SSN ", "123-45-6789", PiiMention.TYPE_US_SSN, false),
      new Sample("IBAN ", "DE89370400440532013000", PiiMention.TYPE_IBAN, false),
      new Sample("SIN: ", "046 454 286", PiiMention.TYPE_CA_SIN, true),
      new Sample("IMEI: ", "490154203237518", PiiMention.TYPE_IMEI, true),
      new Sample("ABA ", "011000015", PiiMention.TYPE_ABA_ROUTING, false),
      new Sample("NHS ", "943 476 5919", PiiMention.TYPE_UK_NHS, false),
      new Sample("host ", "192.168.0.1", PiiMention.TYPE_IPV4, false));

  /**
   * Zero digits of digit blocks outside ASCII: fullwidth, Arabic-Indic, Devanagari and
   * mathematical bold, the last one supplementary.
   */
  private static final int[] OTHER_ZEROS = {0xFF10, 0x0660, 0x0966, 0x1D7CE};

  /**
   * Characters of the Unicode Format category and the soft hyphen: zero width space,
   * zero width joiner, word joiner, byte order mark, left-to-right and right-to-left marks.
   */
  private static final String[] INVISIBLE =
      {"​", "‍", "⁠", "﻿", "‎", "‏", "­"};

  private final PiiExtractor all = PiiPacks.allStructured();

  private static Stream<Sample> samples() {
    return SAMPLES.stream();
  }

  private static Stream<Sample> unlabeledSamples() {
    return SAMPLES.stream().filter(sample -> !sample.labeled());
  }

  private static Stream<Sample> labeledSamples() {
    return SAMPLES.stream().filter(Sample::labeled);
  }

  /**
   * Provides each sample with its digits moved to another digit block.
   *
   * @return The sample and the rewritten text.
   */
  private static Stream<Arguments> otherDigitBlocks() {
    return SAMPLES.stream().flatMap(sample -> Arrays.stream(OTHER_ZEROS)
        .mapToObj(zero -> Arguments.of(sample, withDigitsFrom(sample.text(), zero))));
  }

  /**
   * Provides each sample with an invisible character inside its value.
   *
   * @return The sample, the text with the inserted character and the character's offset.
   */
  private static Stream<Arguments> invisibleInsideValue() {
    return SAMPLES.stream().flatMap(sample -> Stream.of(INVISIBLE).map(invisible -> {
      final int position = sample.prefix().length() + 3;
      final String text = sample.text();
      return Arguments.of(sample, text.substring(0, position) + invisible + text.substring(position),
          position);
    }));
  }

  /**
   * Checks that every sample is reported with its type, so the rewritten forms test something.
   *
   * @param sample The sample.
   */
  @ParameterizedTest
  @MethodSource("samples")
  void testSamplesAreDetected(Sample sample) {
    final List<PiiMention> mentions = all.extract(sample.text());
    Assertions.assertTrue(mentions.stream().anyMatch(m -> m.type().equals(sample.type())),
        mentions::toString);
  }

  /**
   * Checks that digits of other blocks are not values: E.164, ISO/IEC 7812, ISO 13616 and
   * the identity formats are defined over ASCII digits.
   *
   * @param sample The sample.
   * @param rewritten The text with the digits moved to another block.
   */
  @ParameterizedTest
  @MethodSource("otherDigitBlocks")
  void testDigitsOutsideAsciiAreNotValues(Sample sample, String rewritten) {
    Assertions.assertNotEquals(sample.text(), rewritten);
    Assertions.assertEquals(List.of(), all.extract(rewritten), rewritten);
  }

  /**
   * Checks that an invisible character is never inside a value: it is not a group separator,
   * so the digits on both sides are at most separate candidates.
   *
   * @param sample The sample.
   * @param rewritten The text with the character inserted.
   * @param position The offset of the inserted character.
   */
  @ParameterizedTest
  @MethodSource("invisibleInsideValue")
  void testInvisibleCharactersSplitAValue(Sample sample, String rewritten, int position) {
    for (final PiiMention mention : all.extract(rewritten)) {
      Assertions.assertFalse(mention.span().getStart() <= position
          && position < mention.span().getEnd(), sample + " -> " + mention);
    }
  }

  /**
   * Checks that bidirectional marks and other invisible characters next to a value do not
   * hide it and leave the offsets exact.
   *
   * @param sample The sample.
   */
  @ParameterizedTest
  @MethodSource("unlabeledSamples")
  void testInvisibleCharactersAroundAValueDoNotHideIt(Sample sample) {
    for (final String invisible : INVISIBLE) {
      final String rewritten = sample.prefix() + invisible + sample.value() + invisible;
      final PiiMention mention = only(all.extract(rewritten), sample.type(), rewritten);
      Assertions.assertEquals(sample.prefix().length() + 1, mention.span().getStart(), rewritten);
      Assertions.assertEquals(rewritten.length() - 1, mention.span().getEnd(), rewritten);
    }
  }

  /**
   * Checks that an invisible character between a label and its value is not label
   * punctuation, so the label does not apply.
   *
   * @param sample The labeled sample.
   */
  @ParameterizedTest
  @MethodSource("labeledSamples")
  void testInvisibleCharacterAfterALabelDetachesIt(Sample sample) {
    for (final String invisible : INVISIBLE) {
      final String rewritten = sample.prefix() + invisible + sample.value();
      Assertions.assertTrue(all.extract(rewritten).stream()
          .noneMatch(m -> m.type().equals(sample.type())), rewritten);
    }
  }

  /**
   * Checks that a lone surrogate next to a value neither hides it nor fails the scan.
   *
   * @param sample The sample.
   */
  @ParameterizedTest
  @MethodSource("unlabeledSamples")
  void testLoneSurrogatesAroundAValueDoNotHideIt(Sample sample) {
    final String rewritten = sample.prefix() + "\uDC00" + sample.value() + "\uD800";
    final PiiMention mention = only(all.extract(rewritten), sample.type(), rewritten);
    Assertions.assertEquals(sample.prefix().length() + 1, mention.span().getStart(), rewritten);
    Assertions.assertEquals(rewritten.length() - 1, mention.span().getEnd(), rewritten);
  }

  /**
   * Checks that a number after another number and a hyphen is not a candidate, so an
   * order number does not lend its tail to a card number.
   *
   * @param text The text.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Order 12345-4111111111111111", "Order 12345-4111 1111 1111 1111",
      "ref 2024-123456789", "ref 2024-123-45-6789"})
  void testNumberAfterDigitAndHyphenIsNotACandidate(String text) {
    Assertions.assertEquals(List.of(), all.extract(text), text);
  }

  /**
   * Checks that a value after a letter and a hyphen is still a candidate.
   *
   * @param text The text.
   * @param normalized The expected value.
   */
  @ParameterizedTest
  @CsvSource({
      "x-4111111111111111, 4111111111111111",
      "ref-123-45-6789, 123-45-6789"
  })
  void testNumberAfterLetterAndHyphenIsACandidate(String text, String normalized) {
    final List<PiiMention> mentions = all.extract(text);
    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(normalized, mentions.get(0).normalized());
  }

  /**
   * Checks that the scan of a long repetitive input stays linear: a million characters of
   * each pattern complete well inside the timeout.
   *
   * @param pattern The repeated text.
   */
  @ParameterizedTest
  @ValueSource(strings = {"4", "4 ", "41-", "+1 ", "a@", "a.", "DE89", "DE89 3704 ", ":", "ab:",
      "1.", "A", "eyJ.", "eyJhbGciOiJIUzI1NiJ9.", "0x", "bc1q", "1A", "https://user:", "ghp_",
      "AKIA", "4 ", "SIN: ", "IMEI: 4", "😀", "​4"})
  @Timeout(60)
  void testLongRepetitiveInputCompletes(String pattern) {
    final int length = 1_000_000;
    final String text = pattern.repeat(length / pattern.length() + 1).substring(0, length);
    Assertions.assertNotNull(all.extract(text));
  }

  /**
   * Checks that a long run before an {@code @} and a long domain complete.
   *
   * @param tail The text after the long run.
   */
  @ParameterizedTest
  @ValueSource(strings = {"@example.com", "@", ".com"})
  @Timeout(60)
  void testLongEmailPartsComplete(String tail) {
    Assertions.assertNotNull(all.extract("a".repeat(1_000_000) + tail));
    Assertions.assertNotNull(all.extract("jane@" + "a".repeat(1_000_000) + tail));
  }

  /**
   * Finds the one mention of a type.
   *
   * @param mentions The mentions.
   * @param type The type.
   * @param text The scanned text, for the failure message.
   * @return The mention.
   */
  private PiiMention only(List<PiiMention> mentions, String type, String text) {
    final List<PiiMention> ofType = mentions.stream().filter(m -> m.type().equals(type)).toList();
    Assertions.assertEquals(1, ofType.size(), () -> text + " -> " + mentions);
    return ofType.getFirst();
  }

  /**
   * Replaces the ASCII digits of a text by the digits of another block.
   *
   * @param text The text.
   * @param zero The code point of that block's zero.
   * @return The rewritten text.
   */
  private static String withDigitsFrom(String text, int zero) {
    final StringBuilder out = new StringBuilder(text.length() * 2);
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c >= '0' && c <= '9') {
        out.appendCodePoint(zero + c - '0');
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
