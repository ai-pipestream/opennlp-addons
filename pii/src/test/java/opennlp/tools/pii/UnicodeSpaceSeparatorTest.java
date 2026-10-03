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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
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
   * Tabs and line breaks still end a grouped candidate.
   *
   * @param separator The separator that must not join groups.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\t", "\n"})
  void testTabsAndLineBreaksDoNotJoinGroups(String separator) {
    Assertions.assertTrue(new UsIdentityPiiExtractor()
        .extract(spaced("123 45 6789", separator)).isEmpty());
  }
}
