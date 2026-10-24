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
 * Unicode letter/digit boundary and context checks over one text, shared by the PII
 * scanners.
 *
 * <p>Word boundaries use complete code points, including supplementary characters. An
 * instance belongs to one scan of its text and holds no other state.</p>
 */
final class Boundaries {

  private final CharSequence text;

  /**
   * Initializes the checks over a text.
   *
   * @param text The text being scanned.
   */
  Boundaries(CharSequence text) {
    this.text = text;
  }

  /**
   * Checks that a candidate does not continue a word on the left.
   *
   * @param start The candidate start.
   * @return {@code true} if the candidate may start here.
   */
  boolean onWordStart(int start) {
    return start == 0
        || !Character.isLetterOrDigit(Character.codePointBefore(text, start));
  }

  /**
   * Checks that a numeric candidate does not continue a word, a dotted decimal value,
   * a comma-grouped number or a hyphenated number on the left.
   *
   * <p>A number does not start directly after a digit and a hyphen, so the later groups
   * of a hyphenated identifier such as {@code ISBN 978-0-306-40615-7} are not candidates
   * of their own. The trade-off is a number written with a dialing prefix, such as
   * {@code 001-555-123-4567}: its national part is not a candidate either, while the
   * international form {@code +1 555-123-4567} is reported.</p>
   *
   * @param start The candidate start.
   * @return {@code true} if the candidate may start here.
   */
  boolean onNumberStart(int start) {
    if (start == 0) {
      return true;
    }
    final int previous = Character.codePointBefore(text, start);
    if (Character.isLetterOrDigit(previous)) {
      return false;
    }
    return (previous != '.' && previous != ',' && previous != '-')
        || start < 2 || !StringUtil.isAsciiDigit(text.charAt(start - 2));
  }

  /**
   * Checks that a candidate ending at {@code end} does not continue into a letter or
   * digit.
   *
   * @param end The candidate end, exclusive.
   * @return {@code true} if the candidate may end here.
   */
  boolean onEnd(int end) {
    return end >= text.length()
        || !Character.isLetterOrDigit(Character.codePointAt(text, end));
  }

  /**
   * Checks the word boundary and rejects a following separator run continued by a
   * Unicode letter or digit. A terminal separator run may be punctuation.
   *
   * @param end The candidate end, exclusive.
   * @param separator The separator that continues the structure.
   * @return {@code true} if the candidate may end here.
   */
  boolean onEndBefore(int end, char separator) {
    if (!onEnd(end)) {
      return false;
    }
    int p = end;
    while (p < text.length() && text.charAt(p) == separator) {
      p++;
    }
    return p == end || onEnd(p);
  }

  /**
   * Checks the end boundary of a numeric candidate and rejects a continuation into a
   * further digit group, such as a decimal fraction, a comma-grouped number or the next
   * group of a hyphenated identifier.
   *
   * @param end The candidate end, exclusive.
   * @param continuations The separators that continue the number when a digit follows them;
   *                      an ASCII space in it stands for every {@link #isGroupSpace(char)
   *                      group space}.
   * @return {@code true} if the candidate may end here.
   */
  boolean onNumberEnd(int end, String continuations) {
    if (!onEnd(end)) {
      return false;
    }
    if (end + 1 >= text.length()) {
      return true;
    }
    return continuations.indexOf(separatorKind(text.charAt(end))) < 0
        || !StringUtil.isAsciiDigit(text.charAt(end + 1));
  }

  /**
   * Checks that one of the labels directly precedes a candidate, separated only by
   * whitespace and the given punctuation, and does not continue a word on the left.
   *
   * @param start The candidate start.
   * @param labels The accepted labels, in ASCII lowercase.
   * @param punctuation The punctuation that may separate the label and the candidate.
   * @return {@code true} if an isolated label precedes the candidate.
   */
  boolean precededByLabel(int start, String[] labels, String punctuation) {
    int end = start;
    while (end > 0 && (StringUtil.isUnicodeWhitespace(text.charAt(end - 1))
        || punctuation.indexOf(text.charAt(end - 1)) >= 0)) {
      end--;
    }
    for (final String label : labels) {
      final int labelStart = end - label.length();
      if (labelStart >= 0 && Ascii.equalsIgnoreCase(text, labelStart, label)
          && onWordStart(labelStart)) {
        return true;
      }
    }
    return false;
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
