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

package opennlp.tools.coref;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.coref.Mention.Gender;

class CorefLexiconTest {

  private static Map<String, Gender> parse(String table) throws IOException {
    return CorefLexicon.parseFirstNames(new BufferedReader(new StringReader(table)));
  }

  @Test
  void testParsesNamesWithTheirGenderLetter() throws IOException {
    final Map<String, Gender> names = parse("# comment\n\nalice\tf\nbob\tm\n");
    Assertions.assertEquals(Map.of("alice", Gender.FEMALE, "bob", Gender.MALE), names);
  }

  @Test
  void testBundledTableLoadsAndAnswersLookups() {
    Assertions.assertEquals(Gender.FEMALE, CorefLexicon.firstNameGender("mary"));
    Assertions.assertEquals(Gender.MALE, CorefLexicon.firstNameGender("james"));
    Assertions.assertEquals(Gender.UNKNOWN, CorefLexicon.firstNameGender("acme"));
  }

  /** A line made of Unicode whitespace only is skipped like an empty line, not parsed. */
  @ParameterizedTest
  @ValueSource(strings = {"\u00A0", "\u3000", "\u2028"})
  void testSkipsUnicodeBlankLines(String blank) throws IOException {
    Assertions.assertEquals(Map.of("alice", Gender.FEMALE),
        parse("alice\tf\n" + blank + "\n" + blank + blank + "\n"));
  }

  /** A gender letter other than {@code m} or {@code f} is a corrupt table, not a woman. */
  @ParameterizedTest
  @ValueSource(strings = {"alice\tx", "alice\tM", "alice\tfemale", "alice\t", "\tf", "alice f"})
  void testRejectsMalformedEntries(String line) {
    Assertions.assertThrows(IllegalStateException.class, () -> parse(line + "\n"), line);
  }
}
