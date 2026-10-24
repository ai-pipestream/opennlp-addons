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

import opennlp.tools.util.StringUtil;

/**
 * ASCII character classification and case conversion that
 * {@link opennlp.tools.util.StringUtil} does not offer, for scanners of identifiers whose
 * standards define an ASCII alphabet: the RFC 5321 mailbox syntax, the ISO 13616 IBAN
 * alphabet, E.164 and ISO/IEC 7812 digits, hexadecimal, base58, bech32 and base64url. Digits
 * outside ASCII, such as fullwidth digits, are therefore not part of a candidate. Operations
 * are locale-independent and never change the length of the text.
 */
final class Ascii {

  /** The distance between an uppercase and the matching lowercase ASCII letter. */
  private static final int CASE_DISTANCE = 'a' - 'A';

  /** Prevents construction. */
  private Ascii() {
  }

  /**
   * Tests for an uppercase ASCII letter.
   *
   * @param c The character.
   * @return {@code true} for {@code A-Z}.
   */
  static boolean isUpper(char c) {
    return c >= 'A' && c <= 'Z';
  }

  /**
   * Tests for an ASCII letter or digit.
   *
   * @param c The character.
   * @return {@code true} for {@code A-Z}, {@code a-z}, and {@code 0-9}.
   */
  static boolean isLetterOrDigit(char c) {
    return StringUtil.isAsciiLetter(c) || StringUtil.isAsciiDigit(c);
  }

  /**
   * Tests for a hexadecimal digit in either case.
   *
   * @param c The character.
   * @return {@code true} for {@code 0-9}, {@code a-f}, and {@code A-F}.
   */
  static boolean isHexDigit(char c) {
    return StringUtil.isAsciiDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  /**
   * Gets the value of a hexadecimal digit.
   *
   * @param c The character.
   * @return The value {@code 0} to {@code 15}, or {@code -1} if {@code c} is not a
   *         hexadecimal digit.
   */
  static int hexValue(char c) {
    if (StringUtil.isAsciiDigit(c)) {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    return -1;
  }

  /**
   * Converts an ASCII letter to lowercase, leaving other characters unchanged.
   *
   * @param c The character.
   * @return The lowercase form of an ASCII letter, otherwise {@code c}.
   */
  static char toLower(char c) {
    return isUpper(c) ? (char) (c + CASE_DISTANCE) : c;
  }

  /**
   * Converts an ASCII letter to uppercase, leaving other characters unchanged.
   *
   * @param c The character.
   * @return The uppercase form of an ASCII letter, otherwise {@code c}.
   */
  static char toUpper(char c) {
    return StringUtil.isAsciiLowerCase(c) ? (char) (c - CASE_DISTANCE) : c;
  }

  /**
   * Converts the ASCII letters of a sequence to uppercase and leaves every other character
   * unchanged, so the result has the length of the input.
   *
   * @param value The sequence to convert. Must not be {@code null}.
   * @return The non-null converted sequence.
   */
  static String toUpper(CharSequence value) {
    final StringBuilder folded = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      folded.append(toUpper(value.charAt(i)));
    }
    return folded.toString();
  }

  /**
   * Compares ASCII text at a validated offset, ignoring ASCII letter case.
   *
   * @param text The non-null text.
   * @param start The comparison start, with enough remaining text for {@code literal}.
   * @param literal The non-null ASCII text to compare.
   * @return {@code true} if the text range matches {@code literal} ignoring ASCII case.
   */
  static boolean equalsIgnoreCase(CharSequence text, int start, String literal) {
    for (int i = 0; i < literal.length(); i++) {
      if (toLower(text.charAt(start + i)) != toLower(literal.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
