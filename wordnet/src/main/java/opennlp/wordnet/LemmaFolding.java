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
package opennlp.wordnet;

import java.util.List;

import opennlp.tools.util.StringUtil;

/** Shared lemma folding and space-separated field parsing for the WordNet readers. */
final class LemmaFolding {

  /** Not instantiable. */
  private LemmaFolding() {
  }

  /**
   * Converts a written form to the lookup form: lowercase with the locale-independent
   * one-to-one mapping of {@link StringUtil#toLowerCase(CharSequence)}, with the underscore
   * some formats store in multiword lemmas treated as a space. Leading and trailing whitespace
   * is removed and every inner run of underscores or Unicode whitespace
   * ({@link StringUtil#isWhitespace(char)}) becomes one space, so a query typed with a
   * no-break space or tab finds the same lemma as one typed with a plain space.
   *
   * @param writtenForm The form as written in a source file or query. Must not be {@code null}.
   * @return The folded form.
   * @throws IllegalArgumentException Thrown if {@code writtenForm} is {@code null}.
   */
  static String fold(String writtenForm) {
    if (writtenForm == null) {
      throw new IllegalArgumentException("writtenForm must not be null");
    }
    final StringBuilder normalized = new StringBuilder(writtenForm.length());
    boolean pendingSpace = false;
    for (int i = 0; i < writtenForm.length(); i++) {
      final char c = writtenForm.charAt(i);
      if (c == '_' || StringUtil.isWhitespace(c)) {
        pendingSpace = !normalized.isEmpty();
      } else {
        if (pendingSpace) {
          normalized.append(' ');
          pendingSpace = false;
        }
        normalized.append(c);
      }
    }
    return StringUtil.toLowerCase(normalized);
  }

  /**
   * Splits a space-separated field list, collapsing runs of spaces. Only the ASCII space
   * separates fields: both callers parse formats whose separator is defined as U+0020, the
   * WNDB exception lists (wndb(5WN)) and the WN-LMF {@code members} IDREFS attribute, whose
   * tabs and line breaks the XML parser already normalized to spaces.
   *
   * @param value The field list. Must not be {@code null}.
   * @return The non-empty fields in order, never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code value} is {@code null}.
   */
  static List<String> splitOnSpaces(String value) {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    return List.of(StringUtil.splitNonEmpty(value, ' '));
  }
}
