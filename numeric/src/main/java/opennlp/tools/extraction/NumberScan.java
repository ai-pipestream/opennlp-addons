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

package opennlp.tools.extraction;

import java.math.BigDecimal;

import opennlp.tools.commons.Internal;
import opennlp.tools.util.ParamChecks;
import opennlp.tools.util.StringUtil;

/**
 * Scans decimal digits with strict grouping, an optional fractional part, and optional
 * scale markers for the typed extractors.
 *
 * <p>Digits are the decimal digits of every script, the Unicode category {@code Nd}, so
 * Arabic-Indic, Devanagari or fullwidth digits read like ASCII ones; the normalized value
 * is always written in ASCII digits. Which character groups digits and which marks the
 * fraction is the {@link NumberNotation} the scanner is constructed with. Grouping is
 * strict: once a group separator appears, the leading group must have at most three digits
 * and every further group exactly three. A scan that
 * stops at another numeric separator directly followed by a digit fails entirely instead
 * of truncating, because the text continues a number this scanner cannot parse, for
 * example the Indian-grouped {@code 1,00,000}, a repeated decimal separator, or a European
 * {@code 1.234,56} read in {@link NumberNotation#LATIN_US}. Nonbreaking and thin spaces,
 * apostrophes, and Arabic numeric separators between digits also reject the scan;
 * they are not converted to the selected notation. One Unicode whitespace character
 * separates a number from a following scale word.
 * Scientific notation such as {@code 1e-3} is not supported and rejects the scan.
 * With scaling enabled, an immediate suffix ({@code k}, {@code m}, {@code b},
 * {@code bn}) or a following word ({@code thousand} to {@code trillion}) multiplies the
 * value, and an immediate letter that is no scale marker invalidates the scan
 * entirely.</p>
 *
 * @since 3.0.0
 */
@Internal
public final class NumberScan {

  /** The sentinel returned by {@link #codePointAt(CharSequence, int)} out of bounds. */
  public static final int NO_CODE_POINT = -1;

  /** The length of the longest recognized scale word, {@code thousand} and {@code trillion}. */
  private static final int MAX_SCALE_WORD_LENGTH = 8;

  private final NumberNotation notation;

  /**
   * Initializes a scanner for one written convention.
   *
   * @param notation The convention the text groups digits and marks fractions in. Must
   *                 not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code notation} is {@code null}.
   */
  public NumberScan(NumberNotation notation) {
    this.notation = ParamChecks.requireNonNullArg(notation, "notation");
  }

  /**
   * The result of one scan: the normalized value and the exclusive end offset.
   *
   * @param value The normalized numeric value. Never {@code null}.
   * @param end The exclusive offset of the first character after the number.
   */
  public record Result(BigDecimal value, int end) {
  }

  /**
   * Scans a number starting at a position.
   *
   * @param text The text to scan. Must not be {@code null}.
   * @param start The offset of the first digit.
   * @param applyScale {@code true} to consume and apply scale markers, in which case an
   *                   immediate letter that is no scale marker fails the scan;
   *                   {@code false} to stop after the decimal part.
   * @return The scanned {@link Result}, with a dot decimal
   *         separator, or {@code null} when no number starts at {@code start}, the scan
   *         stops at another separator directly followed by a digit, or an immediate
   *         letter suffix is not a scale marker, or a scientific-notation exponent follows.
   */
  public Result parse(CharSequence text, int start, boolean applyScale) {
    final char group = notation.groupSeparator();
    final char decimal = notation.decimalSeparator();
    final StringBuilder normalized = new StringBuilder();
    int i = appendDigits(text, start, normalized);
    if (i == start) {
      return null;
    }
    if (charAt(text, i) == group && normalized.length() <= 3) {
      while (charAt(text, i) == group && groupOfThree(text, i + 1)) {
        i = appendDigits(text, i + 1, normalized);
      }
    }
    if (charAt(text, i) == decimal && Character.isDigit(codePointAt(text, i + 1))) {
      normalized.append('.');
      i = appendDigits(text, i + 1, normalized);
    }
    if ((charAt(text, i) == group || charAt(text, i) == decimal
        || unsupportedSeparator(charAt(text, i)))
        && Character.isDigit(codePointAt(text, i + 1))) {
      // Another separator followed by a digit continues the malformed number. Returning
      // a valid prefix here would let a typed extractor report the wrong value.
      return null;
    }
    if (exponentAt(text, i)
        || ((charAt(text, i) == group || charAt(text, i) == decimal) && exponentAt(text, i + 1))) {
      return null;
    }
    final BigDecimal value = new BigDecimal(normalized.toString());
    return applyScale ? parseScale(text, i, value) : new Result(value, i);
  }

  /**
   * Detects a numeric continuation after a separator or exponent marker. This excludes
   * partial mentions such as the final {@code 000} in {@code 1,00,000} or the signed
   * exponent in {@code 1e-3}.
   *
   * @param text The text. Must not be {@code null}.
   * @param index The candidate start offset.
   * @return {@code true} if a number starting at {@code index} would continue an earlier
   *         one.
   */
  public boolean continuesNumber(CharSequence text, int index) {
    final char before = charAt(text, index - 1);
    if ((before == notation.groupSeparator() || before == notation.decimalSeparator()
        || unsupportedSeparator(before))
        && digitBefore(text, index - 1)) {
      return true;
    }
    final int exponent = index - (before == '+' || before == '-' ? 2 : 1);
    final char marker = charAt(text, exponent);
    if (marker != 'e' && marker != 'E') {
      return false;
    }
    final char mantissaEnd = charAt(text, exponent - 1);
    return digitBefore(text, exponent)
        || ((mantissaEnd == notation.groupSeparator() || mantissaEnd == notation.decimalSeparator())
            && digitBefore(text, exponent - 1));
  }

  /**
   * Checks whether the code point ending at a position is a decimal digit.
   *
   * @param text The text. Must not be {@code null}.
   * @param index The exclusive end offset of the candidate digit.
   * @return {@code true} if a decimal digit ends at {@code index}.
   */
  private boolean digitBefore(CharSequence text, int index) {
    return index > 0 && index <= text.length() && Character.isDigit(Character.codePointBefore(text, index));
  }

  /**
   * Copies a run of decimal digits, of any script, to the normalized buffer as ASCII
   * digits.
   *
   * @param text The text being scanned. Must not be {@code null}.
   * @param start The offset of the first candidate digit.
   * @param normalized The buffer receiving the ASCII digits.
   * @return The exclusive end offset of the run, equal to {@code start} when no digit
   *         starts there.
   */
  private int appendDigits(CharSequence text, int start, StringBuilder normalized) {
    int i = start;
    int cp = codePointAt(text, i);
    while (Character.isDigit(cp)) {
      normalized.append((char) ('0' + Character.digit(cp, 10)));
      i += Character.charCount(cp);
      cp = codePointAt(text, i);
    }
    return i;
  }

  /**
   * Checks for an ASCII exponent marker followed by an optional sign and digits.
   *
   * @param text The text being scanned.
   * @param start The candidate exponent marker offset.
   * @return Whether a scientific-notation exponent starts at the offset.
   */
  private boolean exponentAt(CharSequence text, int start) {
    final char marker = charAt(text, start);
    if (marker != 'e' && marker != 'E') {
      return false;
    }
    final char sign = charAt(text, start + 1);
    return Character.isDigit(codePointAt(text, start + (sign == '+' || sign == '-' ? 2 : 1)));
  }

  /**
   * Identifies numeric separators that neither supported notation consumes.
   *
   * @param separator The character between digits.
   * @return Whether the character indicates unsupported grouping or a decimal separator.
   */
  private boolean unsupportedSeparator(char separator) {
    return switch (separator) {
      case '\u00A0', '\u2009', '\u202F', '\'', '\u2019', '\u066B', '\u066C' -> true;
      default -> false;
    };
  }

  /**
   * Applies an immediate suffix scale or a following scale word.
   *
   * @param text The text being scanned.
   * @param end The exclusive offset behind the digits scanned so far.
   * @param value The value scanned so far.
   * @return The scaled {@link Result}, the unscaled one when no scale marker follows, or
   *         {@code null} when an immediate letter suffix is not a scale marker.
   */
  private Result parseScale(CharSequence text, int end, BigDecimal value) {
    final int suffix = codePointAt(text, end);
    if (Character.isLetter(suffix)) {
      final boolean bn = (suffix == 'b' || suffix == 'B')
          && (charAt(text, end + 1) == 'n' || charAt(text, end + 1) == 'N');
      final int suffixEnd = end + (bn ? 2 : 1);
      final long scale = switch (suffix) {
        case 'k', 'K' -> 1_000L;
        case 'm', 'M' -> 1_000_000L;
        case 'b', 'B' -> 1_000_000_000L;
        default -> 0L;
      };
      if (scale == 0L || !boundaryAfter(text, suffixEnd)) {
        return null;
      }
      return new Result(value.multiply(BigDecimal.valueOf(scale)), suffixEnd);
    }
    if (whitespaceAt(text, end)) {
      final Result worded = parseScaleWord(text, end + 1, value);
      if (worded != null) {
        return worded;
      }
    }
    return new Result(value, end);
  }

  /**
   * Parses a scale word after the number; absence is not an error.
   *
   * @param text The text being scanned.
   * @param start The offset of the first letter of the candidate scale word.
   * @param value The value scanned so far.
   * @return The scaled {@link Result}, or {@code null} when no scale word starts at
   *         {@code start}.
   */
  private Result parseScaleWord(CharSequence text, int start, BigDecimal value) {
    final int end = wordEnd(text, start, MAX_SCALE_WORD_LENGTH);
    if (end < 0) {
      return null;
    }
    final long scale = switch (StringUtil.toLowerCase(text.subSequence(start, end))) {
      case "thousand" -> 1_000L;
      case "million" -> 1_000_000L;
      case "billion" -> 1_000_000_000L;
      case "trillion" -> 1_000_000_000_000L;
      default -> 0L;
    };
    if (scale == 0L) {
      return null;
    }
    return new Result(value.multiply(BigDecimal.valueOf(scale)), end);
  }

  /**
   * Scans a Unicode letter token with a UTF-16 length limit.
   *
   * @param text The text being scanned. Must not be {@code null}.
   * @param start The offset of the first candidate letter.
   * @param maxLength The maximum run length in UTF-16 code units.
   * @return The exclusive end offset, or {@code -1} for an empty or overlong token,
   *         or a token followed by a number, combining mark, or identifier connector.
   */
  public int wordEnd(CharSequence text, int start, int maxLength) {
    int i = start;
    while (Character.isLetter(codePointAt(text, i)) && i - start < maxLength) {
      i += Character.charCount(codePointAt(text, i));
    }
    return i == start || i - start > maxLength || !boundaryAfter(text, i) ? -1 : i;
  }

  /**
   * Checks whether exactly three digits, not followed by a fourth, start at a position.
   *
   * @param text The text being scanned.
   * @param start The offset of the first digit of the candidate group.
   * @return {@code true} if a complete group of three digits starts at {@code start}.
   */
  private boolean groupOfThree(CharSequence text, int start) {
    int i = start;
    for (int count = 0; count < 3; count++) {
      final int cp = codePointAt(text, i);
      if (!Character.isDigit(cp)) {
        return false;
      }
      i += Character.charCount(cp);
    }
    return !Character.isDigit(codePointAt(text, i));
  }

  /**
   * Checks whether the preceding character allows a match to start here. Combining
   * marks are classified with the preceding base character.
   *
   * @param text The text. Must not be {@code null}.
   * @param index The candidate start offset.
   * @return {@code true} if a match may start at {@code index}.
   */
  public boolean boundaryBefore(CharSequence text, int index) {
    return !wordContinuation(baseCodePointBefore(text, index));
  }

  /**
   * Checks whether a minus sign at the position may open a negative amount: the
   * position is at the text start or the preceding base character could not have ended a
   * numeric mention. A letter, a digit, a currency symbol, or a percent sign before
   * the minus makes it a range or prose hyphen, so {@code 50\u20AC-60\u20AC} (euro
   * signs) and {@code 5%-10%} read as two positive mentions rather than one positive
   * and one negated one. Combining marks use the preceding base character's category.
   *
   * @param text The text. Must not be {@code null}.
   * @param index The offset of the minus sign.
   * @return {@code true} if a negative amount may start at {@code index}.
   */
  public boolean signBoundaryBefore(CharSequence text, int index) {
    final int cp = baseCodePointBefore(text, index);
    return !wordContinuation(cp)
        && Character.getType(cp) != Character.CURRENCY_SYMBOL
        && cp != '%';
  }

  /**
   * Checks whether a match may end here: the position is at the text end or the code
   * point at it is not a letter, number, combining mark, or identifier connector.
   *
   * @param text The text. Must not be {@code null}.
   * @param index The candidate exclusive end offset.
   * @return {@code true} if a match may end at {@code index}.
   */
  public boolean boundaryAfter(CharSequence text, int index) {
    final int cp = codePointAt(text, index);
    return cp == NO_CODE_POINT || !wordContinuation(cp);
  }

  /**
   * Reads the preceding code point after skipping combining marks.
   *
   * @param text The text being scanned. Must not be {@code null}.
   * @param index The UTF-16 offset between zero and the text length, inclusive.
   * @return The preceding non-mark code point, or {@link #NO_CODE_POINT} if unavailable.
   */
  public int baseCodePointBefore(CharSequence text, int index) {
    int previous = index;
    while (previous > 0) {
      final int cp = Character.codePointBefore(text, previous);
      if (!combiningMark(cp)) {
        return cp;
      }
      previous -= Character.charCount(cp);
    }
    return NO_CODE_POINT;
  }

  /**
   * Tests the Unicode nonspacing, spacing, and enclosing mark categories.
   *
   * @param cp The code point to classify.
   * @return Whether the code point is a combining mark.
   */
  private boolean combiningMark(int cp) {
    return switch (Character.getType(cp)) {
      case Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK,
          Character.ENCLOSING_MARK -> true;
      default -> false;
    };
  }

  /**
   * Identifies characters that cannot be discarded from a numeric or word token.
   *
   * @param cp The code point at a candidate boundary.
   * @return Whether the code point continues a token.
   */
  private boolean wordContinuation(int cp) {
    if (Character.isLetterOrDigit(cp) || combiningMark(cp)) {
      return true;
    }
    return switch (Character.getType(cp)) {
      case Character.LETTER_NUMBER, Character.OTHER_NUMBER,
          Character.CONNECTOR_PUNCTUATION -> true;
      default -> false;
    };
  }

  /**
   * Checks for the one-character gap between the parts of a mention: a code unit with the
   * Unicode {@code White_Space} property, so a no-break, thin, or ideographic space or a
   * tab separates like an ASCII space. Out of bounds counts as whitespace, matching the
   * {@link #charAt(CharSequence, int)} sentinel; the following part then fails to scan.
   *
   * @param text The text. Must not be {@code null}.
   * @param index The offset to read.
   * @return {@code true} if the char at {@code index} is whitespace or out of bounds.
   */
  public boolean whitespaceAt(CharSequence text, int index) {
    return StringUtil.isUnicodeWhitespace(charAt(text, index));
  }

  /**
   * Reads the char at a position, returning a space as an out-of-bounds sentinel so
   * scan loops need no per-step bounds checks.
   *
   * @param text The text. Must not be {@code null}.
   * @param index The offset to read.
   * @return The char at {@code index}, or a space when out of bounds.
   */
  public char charAt(CharSequence text, int index) {
    return index >= 0 && index < text.length() ? text.charAt(index) : ' ';
  }

  /**
   * Reads the code point at a position.
   *
   * @param text The text. Must not be {@code null}.
   * @param index The offset to read.
   * @return The code point at {@code index}, or {@link #NO_CODE_POINT} when out of
   *         bounds.
   */
  public int codePointAt(CharSequence text, int index) {
    return index >= 0 && index < text.length()
        ? Character.codePointAt(text, index) : NO_CODE_POINT;
  }
}
