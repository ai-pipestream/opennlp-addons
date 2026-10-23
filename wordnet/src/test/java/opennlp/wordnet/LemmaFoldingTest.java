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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import opennlp.tools.util.WhitespaceMode;
import opennlp.tools.wordnet.WordNetPOS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests lemma folding shared by the readers, exception lists, and lookup index. */
public class LemmaFoldingTest {

  @Test
  void testFoldUsesLocaleIndependentLowercaseAndSpacesForUnderscores() {
    assertEquals("mice", LemmaFolding.fold("MICE"));
    assertEquals("domestic dog", LemmaFolding.fold("Domestic_Dog"));
    assertEquals("attorney general", LemmaFolding.fold("attorney_general"));
    assertEquals("dog", LemmaFolding.fold("dog"));
    assertEquals("", LemmaFolding.fold(""));
  }

  @Test
  void testFoldNormalizesUnicodeWhitespaceInQueries() {
    assertEquals("domestic dog", LemmaFolding.fold("domestic\u00A0dog"));
    assertEquals("domestic dog", LemmaFolding.fold("domestic\tdog"));
    assertEquals("domestic dog", LemmaFolding.fold("domestic \u2003 dog"));
    assertEquals("dog", LemmaFolding.fold("\u3000dog\u2009"));
    assertEquals("dog", LemmaFolding.fold(" dog\n"));
    assertEquals("domestic dog", LemmaFolding.fold("domestic_ dog"));
    assertEquals("", LemmaFolding.fold("\u00A0 "));
  }

  @Test
  void testSplitOnSpacesCollapsesRunsAndIgnoresEdges() {
    assertEquals(List.of("a", "b", "c"), LemmaFolding.splitOnSpaces("a b c"));
    assertEquals(List.of("a", "b"), LemmaFolding.splitOnSpaces("a   b"));
    assertEquals(List.of("a"), LemmaFolding.splitOnSpaces("a"));
    assertEquals(List.of("a"), LemmaFolding.splitOnSpaces(" a "));
    assertEquals(List.of(), LemmaFolding.splitOnSpaces(""));
    assertEquals(List.of(), LemmaFolding.splitOnSpaces("   "));
  }

  @Test
  void testLemmaKeyAndExceptionLookupAgreeOnTheFold() {
    assertEquals(InMemoryWordNetLexicon.LemmaKey.of("Domestic_Dog", WordNetPOS.NOUN),
        InMemoryWordNetLexicon.LemmaKey.of(LemmaFolding.fold("DOMESTIC_DOG"), WordNetPOS.NOUN));
  }

  /**
   * Folding follows the Unicode White_Space property whatever the active
   * {@link WhitespaceMode}: U+0085 (next line) is not whitespace under the legacy
   * definition, but it still separates and pads a lemma like a space.
   */
  @Test
  @ResourceLock(WhitespaceMode.MODE_PROPERTY)
  void testFoldIgnoresTheWhitespaceMode() {
    final WhitespaceMode previous = WhitespaceMode.current();
    try {
      WhitespaceMode.setActive(WhitespaceMode.LEGACY);
      assertEquals("domestic dog", LemmaFolding.fold("\u0085domestic\u0085dog\u0085"));
      assertEquals("", LemmaFolding.fold("\u0085"));
    } finally {
      WhitespaceMode.setActive(previous);
    }
  }

  @Test
  void testFoldRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> LemmaFolding.fold(null));
  }

  @Test
  void testSplitOnSpacesRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> LemmaFolding.splitOnSpaces(null));
  }
}
