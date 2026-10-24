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

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks that digit groups and labels may be separated by any Unicode space
 * separator, not only the ASCII space. No-break spaces are common between digit
 * groups (French and Swiss typography, word processors), and CJK text uses the
 * ideographic space.
 */
public class UnicodeSpaceSeparatorTest {

  /**
   * Builds a candidate text with every ASCII space replaced by {@code space}.
   *
   * @param template The text written with ASCII spaces.
   * @param space The replacement space.
   * @return The rewritten text.
   */
  private static String spaced(String template, String space) {
    return template.replace(" ", space);
  }

  /**
   * Asserts a single mention covering the whole text.
   *
   * @param mentions The extracted mentions.
   * @param text The candidate text.
   * @param type The expected type.
   * @param normalized The expected normalized value.
   */
  private static void assertWhole(List<PiiMention> mentions, String text, String type,
      String normalized) {
    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(type, mentions.get(0).type(), text);
    Assertions.assertEquals(normalized, mentions.get(0).normalized(), text);
    Assertions.assertEquals(0, mentions.get(0).span().getStart(), text);
    Assertions.assertEquals(text.length(), mentions.get(0).span().getEnd(), text);
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", " ", " ", "　"})
  void testCardGroupsSeparatedByUnicodeSpace(String space) {
    final String text = spaced("4111 1111 1111 1111", space);
    assertWhole(new CursorPiiExtractor().extract(text), text,
        PiiMention.TYPE_CARD, "4111111111111111");
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", " ", " ", "　"})
  void testIbanGroupsSeparatedByUnicodeSpace(String space) {
    final String text = spaced("DE89 3704 0044 0532 0130 00", space);
    final List<PiiMention> mentions = new CursorPiiExtractor().extract(text);
    Assertions.assertTrue(mentions.stream().anyMatch(m ->
        PiiMention.TYPE_IBAN.equals(m.type())
            && "DE89370400440532013000".equals(m.normalized())
            && m.span().getStart() == 0 && m.span().getEnd() == text.length()), text);
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", " ", " ", "　"})
  void testPhoneGroupsSeparatedByUnicodeSpace(String space) {
    final String text = spaced("+33 6 12 34 56 78", space);
    assertWhole(new CursorPiiExtractor().extract(text), text,
        PiiMention.TYPE_PHONE, "+33612345678");
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", " ", "　"})
  void testNhsGroupsSeparatedByUnicodeSpace(String space) {
    final String text = spaced("943 476 5919", space);
    assertWhole(new EuIdentityPiiExtractor().extract(text), text,
        PiiMention.TYPE_UK_NHS, "9434765919");
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", " ", "　"})
  void testSsnGroupsSeparatedByUnicodeSpace(String space) {
    final String text = spaced("123 45 6789", space);
    assertWhole(new UsIdentityPiiExtractor().extract(text), text,
        PiiMention.TYPE_US_SSN, "123-45-6789");
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", " ", "　"})
  void testImeiLabelAndGroupsSeparatedByUnicodeSpace(String space) {
    final String text = spaced("IMEI: 35 693803 564380 9", space);
    final List<PiiMention> mentions = new DevicePiiExtractor().extract(text);
    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals("356938035643809", mentions.get(0).normalized(), text);
    Assertions.assertEquals(text.length(), mentions.get(0).span().getEnd(), text);
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", " ", "　"})
  void testSinLabelAndGroupsSeparatedByUnicodeSpace(String space) {
    final String text = spaced("SIN: 046 454 286", space);
    final List<PiiMention> mentions = new CaIdentityPiiExtractor().extract(text);
    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals("046454286", mentions.get(0).normalized(), text);
    Assertions.assertEquals(text.length(), mentions.get(0).span().getEnd(), text);
  }

  /**
   * A grouped identifier must not continue after a Unicode space into another
   * digit group, the same as after an ASCII space.
   *
   * @param space The space between the identifier and the trailing digits.
   */
  @ParameterizedTest
  @ValueSource(strings = {" ", " ", "　"})
  void testGroupedNhsRejectsTrailingDigitGroup(String space) {
    Assertions.assertTrue(new EuIdentityPiiExtractor()
        .extract("943 476 5919" + space + "12").isEmpty());
  }

  /**
   * Provides grouped candidates of every detector that accepts a space between groups.
   *
   * @return The extractor and the candidate written with ASCII spaces.
   */
  private static Stream<Arguments> groupedCandidates() {
    return Stream.of(
        Arguments.of(new CursorPiiExtractor(Set.of(PiiMention.TYPE_IBAN)),
            "DE89 3704 0044 0532 0130 00"),
        Arguments.of(new CursorPiiExtractor(Set.of(PiiMention.TYPE_CARD)), "4111 1111 1111 1111"),
        Arguments.of(new CursorPiiExtractor(Set.of(PiiMention.TYPE_PHONE)), "+33 6 12 34 56 78"),
        Arguments.of(new EuIdentityPiiExtractor(), "943 476 5919"),
        Arguments.of(new UsIdentityPiiExtractor(), "123 45 6789"),
        Arguments.of(new CaIdentityPiiExtractor(), "SIN: 046 454 286"),
        Arguments.of(new DevicePiiExtractor(), "IMEI: 35 693803 564380 9"));
  }

  /**
   * Tabs and line breaks still end a grouped candidate, for every detector. The labels
   * of SIN and IMEI accept any Unicode whitespace before the value; the value itself does
   * not.
   *
   * @param extractor The detector.
   * @param template The candidate written with ASCII spaces.
   */
  @ParameterizedTest
  @MethodSource("groupedCandidates")
  void testTabsAndLineBreaksDoNotJoinGroups(PiiExtractor extractor, String template) {
    for (final String separator : List.of("\t", "\n", "\r\n", "\u000b", "\f", "\u0085")) {
      final String text = spaced(template, separator);
      Assertions.assertEquals(List.of(), extractor.extract(text), text);
    }
  }
}
