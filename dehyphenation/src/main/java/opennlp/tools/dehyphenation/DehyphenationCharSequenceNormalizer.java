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

import java.io.Serial;

import opennlp.tools.util.StringUtil;
import opennlp.tools.util.normalizer.AlignedText;
import opennlp.tools.util.normalizer.Alignment;
import opennlp.tools.util.normalizer.CodePointSet;
import opennlp.tools.util.normalizer.OffsetAwareNormalizer;
import opennlp.tools.util.normalizer.UnicodeWhitespace;

/**
 * Joins words split by a hyphen and a line break, such as
 * {@code "litiga-\ntion"} to {@code "litigation"}.
 *
 * <p>The hyphen must have a letter, optionally followed by combining marks, immediately
 * before it and a forced line break immediately after it. Horizontal whitespace may
 * precede the next letter.
 * Supported hyphens are {@code U+002D HYPHEN-MINUS}, {@code U+00AD SOFT HYPHEN},
 * {@code U+2010 HYPHEN}, and the hyphens other scripts use at a line end:
 * {@code U+058A ARMENIAN HYPHEN}, {@code U+1400 CANADIAN SYLLABICS HYPHEN},
 * {@code U+2E17 DOUBLE OBLIQUE HYPHEN} (Fraktur) and
 * {@code U+10EAD YEZIDI HYPHENATION MARK}. Dashes, {@code U+2011 NON-BREAKING HYPHEN},
 * and the hyphens that join compounds or names, such as {@code U+05BE} and
 * {@code U+30A0}, are excluded.</p>
 *
 * <p>Breaks follow {@link UnicodeWhitespace#lineBreaks()}; CRLF counts as one.
 * Additional line breaks prevent a join. Indentation follows
 * {@link StringUtil#isUnicodeWhitespace(char)}, excluding line breaks.
 * Letter checks support supplementary code points. Offsets use UTF-16 code units.</p>
 *
 * <p>No dictionary is used: {@code "well-\nknown"} becomes {@code "wellknown"}.
 * Text without a matching break is returned without copying. Aligned normalization
 * maps joined-word spans back to the original text, including the deleted break.
 * Use {@link opennlp.tools.dehyphenation.RetokenizingTermVectorAnnotator} to build
 * term vectors from the joined words.</p>
 *
 * @since 3.0.0
 */
public class DehyphenationCharSequenceNormalizer implements OffsetAwareNormalizer {

  @Serial
  private static final long serialVersionUID = 3659585484250150798L;

  private static final String NULL_TEXT_MESSAGE = "The text must not be null.";

  /** The hyphens that may end a line inside a hyphenated word. */
  private static final CodePointSet HYPHENS =
      CodePointSet.of(0x002D, 0x00AD, 0x058A, 0x1400, 0x2010, 0x2E17, 0x10EAD);

  private static final CodePointSet LINE_BREAKS = UnicodeWhitespace.lineBreakCodePointSet();

  private static final DehyphenationCharSequenceNormalizer INSTANCE =
      new DehyphenationCharSequenceNormalizer();

  /** {@return the shared, stateless instance} */
  public static DehyphenationCharSequenceNormalizer getInstance() {
    return INSTANCE;
  }

  /** {@return the shared instance after deserialization} */
  @Serial
  private Object readResolve() {
    return INSTANCE;
  }

  /**
   * {@inheritDoc}
   *
   * Returns {@code text} without copying when no join is needed.
   */
  @Override
  public CharSequence normalize(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException(NULL_TEXT_MESSAGE);
    }
    final int length = text.length();
    StringBuilder out = null;
    int cursor = 0;
    int i = 0;
    while (i < length) {
      final int joinEnd = joinEditEnd(text, i);
      if (joinEnd >= 0) {
        if (out == null) {
          out = new StringBuilder(length);
        }
        out.append(text, cursor, i);
        cursor = i = joinEnd;
      } else {
        i++;
      }
    }
    return out == null ? text : out.append(text, cursor, length).toString();
  }

  /**
   * {@inheritDoc}
   *
   * Joined-word spans map back to the original text, including the deleted break.
   */
  @Override
  public AlignedText normalizeAligned(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException(NULL_TEXT_MESSAGE);
    }
    final int length = text.length();
    final StringBuilder out = new StringBuilder(length);
    final Alignment.Builder alignment = new Alignment.Builder(length);
    int cursor = 0;
    int i = 0;
    while (i < length) {
      final int joinEnd = joinEditEnd(text, i);
      if (joinEnd >= 0) {
        out.append(text, cursor, i);
        alignment.equal(i - cursor);
        alignment.replace(joinEnd - i, 0);
        cursor = i = joinEnd;
      } else {
        i++;
      }
    }
    out.append(text, cursor, length);
    alignment.equal(length - cursor);
    return new AlignedText(text, out.toString(), alignment.build(length));
  }

  /**
   * Checks for a hyphenation break between letters. Combining marks after the letter
   * before the hyphen are allowed.
   *
   * @param text The input text.
   * @param hyphen The candidate hyphen offset.
   * @return The offset after the deletion, or {@code -1} if no join is possible.
   */
  private int joinEditEnd(CharSequence text, int hyphen) {
    final int codePoint = Character.codePointAt(text, hyphen);
    if (!HYPHENS.contains(codePoint)) {
      return -1;
    }
    final int letterEnd = skipMarksBackward(text, hyphen);
    if (letterEnd == 0 || !Character.isLetter(Character.codePointBefore(text, letterEnd))) {
      return -1;
    }
    final int length = text.length();
    int end = hyphen + Character.charCount(codePoint);
    if (end >= length) {
      return -1;
    }
    final char breakStart = text.charAt(end);
    if (!isLineBreak(breakStart)) {
      return -1;
    }
    end++;
    if (breakStart == '\r' && end < length && text.charAt(end) == '\n') {
      end++;  // CRLF is one break, not two
    }
    while (end < length && isHorizontalWhitespace(text.charAt(end))) {
      end++;
    }
    if (end >= length || !Character.isLetter(Character.codePointAt(text, end))) {
      return -1;
    }
    return end;
  }

  /**
   * Steps back over the combining marks that end before an offset, so that a letter in
   * decomposed form, such as {@code "e\u0301"}, counts as a letter.
   *
   * @param text The input text.
   * @param end The offset after the last code point to inspect.
   * @return The offset after the last code point before {@code end} that is not a mark.
   */
  private int skipMarksBackward(CharSequence text, int end) {
    while (end > 0) {
      final int codePoint = Character.codePointBefore(text, end);
      if (!isMark(codePoint)) {
        break;
      }
      end -= Character.charCount(codePoint);
    }
    return end;
  }

  /**
   * Checks for a combining mark.
   *
   * @param codePoint The code point to check.
   * @return Whether the code point has the general category Mn, Mc or Me.
   */
  private boolean isMark(int codePoint) {
    final int type = Character.getType(codePoint);
    return type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK;
  }

  /**
   * Checks for a forced line break.
   *
   * @param c The character to check.
   * @return Whether the character is in {@link #LINE_BREAKS}.
   */
  private boolean isLineBreak(char c) {
    return LINE_BREAKS.contains(c);
  }

  /**
   * Checks for indentation characters.
   *
   * @param c The character to check.
   * @return Whether the character is Unicode whitespace but not a forced line break.
   */
  private boolean isHorizontalWhitespace(char c) {
    return StringUtil.isUnicodeWhitespace(c) && !isLineBreak(c);
  }
}
