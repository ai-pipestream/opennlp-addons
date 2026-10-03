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

/**
 * Unicode letter/digit boundary checks shared by PII scanners.
 *
 * <p>Word boundaries use complete code points, including supplementary characters.</p>
 */
final class Boundaries {

  /** Prevents construction of this utility class. */
  private Boundaries() {
  }

  /**
   * Checks that a candidate does not continue a word on the left.
   *
   * @param text The text being scanned.
   * @param start The candidate start.
   * @return {@code true} if the candidate may start here.
   */
  static boolean onWordStart(CharSequence text, int start) {
    return start == 0
        || !Character.isLetterOrDigit(Character.codePointBefore(text, start));
  }

  /**
   * Checks that a numeric candidate does not continue a word, a dotted decimal value,
   * or a comma-grouped number on the left.
   *
   * @param text The text being scanned.
   * @param start The candidate start.
   * @return {@code true} if the candidate may start here.
   */
  static boolean onNumberStart(CharSequence text, int start) {
    if (start == 0) {
      return true;
    }
    final int previous = Character.codePointBefore(text, start);
    if (Character.isLetterOrDigit(previous)) {
      return false;
    }
    return (previous != '.' && previous != ',')
        || start < 2 || !Ascii.isDigit(text.charAt(start - 2));
  }

  /**
   * Checks that a candidate ending at {@code end} does not continue into a letter or
   * digit.
   *
   * @param text The text being scanned.
   * @param end The candidate end, exclusive.
   * @return {@code true} if the candidate may end here.
   */
  static boolean onEnd(CharSequence text, int end) {
    return end >= text.length()
        || !Character.isLetterOrDigit(Character.codePointAt(text, end));
  }

  /**
   * Checks the word boundary and rejects a following separator run continued by a
   * Unicode letter or digit. A terminal separator run may be punctuation.
   *
   * @param text The text being scanned.
   * @param end The candidate end, exclusive.
   * @param separator The separator that continues the structure.
   * @return {@code true} if the candidate may end here.
   */
  static boolean onEndBefore(CharSequence text, int end, char separator) {
    if (!onEnd(text, end)) {
      return false;
    }
    int p = end;
    while (p < text.length() && text.charAt(p) == separator) {
      p++;
    }
    return p == end || onEnd(text, p);
  }

  /**
   * Tests for a space that may separate digit groups: any Unicode space separator
   * ({@code Zs}), such as the ASCII space, a no-break space, a narrow no-break space,
   * a figure space or an ideographic space. Tabs and line breaks are not group
   * separators, so they still end a candidate.
   *
   * @param c The character.
   * @return {@code true} if {@code c} is a Unicode space separator.
   */
  static boolean isGroupSpace(char c) {
    return Character.getType(c) == Character.SPACE_SEPARATOR;
  }

  /**
   * Maps a group separator to the form used to check consistent separators, so that
   * every {@link #isGroupSpace(char) group space} counts as the same separator.
   *
   * @param c The separator.
   * @return The ASCII space for a group space, otherwise {@code c}.
   */
  static char separatorKind(char c) {
    return isGroupSpace(c) ? ' ' : c;
  }
}
