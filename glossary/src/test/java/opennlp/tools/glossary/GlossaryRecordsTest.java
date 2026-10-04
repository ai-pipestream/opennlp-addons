/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
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

package opennlp.tools.glossary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;
import opennlp.tools.util.WhitespaceMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests public glossary record validation and value preservation. */
class GlossaryRecordsTest {

  private static final Span SPAN = new Span(2, 8);

  /**
   * Rejects missing and whitespace-only identifiers and terms on both records.
   *
   * @param invalid The invalid field value.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\r\n", "\u00A0", "\u2003", "\u202F", "\u3000"})
  void testInvalidText(String invalid) {
    assertThrows(IllegalArgumentException.class, () -> new GlossaryEntry(invalid, "term"));
    assertThrows(IllegalArgumentException.class, () -> new GlossaryEntry("ID", invalid));
    assertThrows(IllegalArgumentException.class, () -> new GlossaryMatch(SPAN, invalid, "term"));
    assertThrows(IllegalArgumentException.class, () -> new GlossaryMatch(SPAN, "ID", invalid));
  }

  /**
   * The blank checks follow the Unicode White_Space property whatever the active
   * {@link WhitespaceMode}: U+0085 (next line) is not whitespace under the legacy
   * definition, but an identifier or term made of it is still blank.
   */
  @Test
  @ResourceLock(WhitespaceMode.MODE_PROPERTY)
  void testBlankChecksIgnoreTheWhitespaceMode() {
    final WhitespaceMode previous = WhitespaceMode.current();
    try {
      WhitespaceMode.setActive(WhitespaceMode.LEGACY);
      assertThrows(IllegalArgumentException.class, () -> new GlossaryEntry("\u0085", "term"));
      assertThrows(IllegalArgumentException.class, () -> new GlossaryEntry("ID", "\u0085"));
      assertThrows(IllegalArgumentException.class,
          () -> new GlossaryMatch(SPAN, "\u0085", "term"));
      assertThrows(IllegalArgumentException.class, () -> new GlossaryMatch(SPAN, "ID", "\u0085"));
    } finally {
      WhitespaceMode.setActive(previous);
    }
  }

  /** A match requires an original-text span. */
  @Test
  void testMissingSpan() {
    assertThrows(IllegalArgumentException.class, () -> new GlossaryMatch(null, "ID", "term"));
  }

  /**
   * Preserves registered forms without trimming, case conversion, or Unicode normalization.
   *
   * @param term The registered surface form.
   */
  @ParameterizedTest
  @ValueSource(strings = {" cat ", "CAT", "cafe\u0301", "\uD801\uDC00", "New York, NY", "+++"})
  void testValuesPreserved(String term) {
    final GlossaryEntry entry = new GlossaryEntry(" ID ", term);
    final GlossaryMatch match = new GlossaryMatch(SPAN, entry.id(), entry.term());
    assertEquals(" ID ", entry.id());
    assertEquals(term, entry.term());
    assertEquals(entry.id(), match.id());
    assertEquals(term, match.term());
    assertSame(SPAN, match.span());
    assertEquals(entry, new GlossaryEntry(" ID ", term));
    assertEquals(match, new GlossaryMatch(new Span(2, 8), " ID ", term));
  }
}
