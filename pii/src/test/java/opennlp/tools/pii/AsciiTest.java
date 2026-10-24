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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.StringUtil;

public class AsciiTest {

  @Test
  void testClassificationAgreesWithTheJdkOverTheAsciiRange() {
    for (char c = 0; c < 128; c++) {
      Assertions.assertEquals(Character.isUpperCase(c), Ascii.isUpper(c), "upper " + (int) c);
      Assertions.assertEquals(Character.isLetterOrDigit(c), Ascii.isLetterOrDigit(c),
          "letterOrDigit " + (int) c);
      Assertions.assertEquals(Character.digit(c, 16), Ascii.hexValue(c), "hex " + (int) c);
      Assertions.assertEquals(Ascii.hexValue(c) >= 0, Ascii.isHexDigit(c), "isHex " + (int) c);
    }
  }

  @ParameterizedTest
  @ValueSource(chars = {'é', 'É', 'ı', 'İ', '中', '٠', 'Ⅰ',
      '１', 'Ａ'})
  void testNonAsciiCharactersAreNeitherLetterNorDigit(char c) {
    Assertions.assertFalse(Ascii.isUpper(c));
    Assertions.assertFalse(Ascii.isLetterOrDigit(c));
    Assertions.assertFalse(Ascii.isHexDigit(c));
    Assertions.assertEquals(-1, Ascii.hexValue(c));
  }

  @ParameterizedTest
  @CsvSource({
      "A, a", "Z, z", "M, m", "a, a", "z, z", "0, 0", "-, -", "@, @"
  })
  void testFoldsAsciiLettersToLowercase(char input, char expected) {
    Assertions.assertEquals(expected, Ascii.toLower(input));
  }

  @ParameterizedTest
  @CsvSource({
      "a, A", "z, Z", "m, M", "A, A", "Z, Z", "0, 0", "-, -", "@, @"
  })
  void testFoldsAsciiLettersToUppercase(char input, char expected) {
    Assertions.assertEquals(expected, Ascii.toUpper(input));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "AKIAIOSFODNN7EXAMPLE",
      "00:1b:44:11:3a:b7",
      "0xAbCdEf0123456789",
      "MiXeD case With SPACES",
      ""})
  void testSequenceFoldingMatchesTheUnicodeFoldForAsciiInput(String value) {
    Assertions.assertEquals(StringUtil.toUpperCase(value), Ascii.toUpper(value));
  }

  /** Verifies that folding stays ASCII-only, unlike the Unicode fold of {@link StringUtil}. */
  @Test
  void testFoldingLeavesNonAsciiLettersUnchanged() {
    Assertions.assertEquals("STRAßE", Ascii.toUpper("straße"));
    Assertions.assertEquals("éCOLE", Ascii.toUpper("école"));
    Assertions.assertEquals("ÉCOLE", StringUtil.toUpperCase("école"));
  }

  @ParameterizedTest
  @CsvSource({
      "'\uD83D\uDE00sIn', 2, SIN, true",
      "iMeI, 0, imei, true",
      "'sİn', 0, sin, false",
      "'ımei', 0, imei, false"
  })
  void testComparesAsciiLettersIgnoringCaseOnly(String text, int start, String literal,
      boolean expected) {
    Assertions.assertEquals(expected, Ascii.equalsIgnoreCase(text, start, literal));
  }
}
