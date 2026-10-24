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
package opennlp.embeddings;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.InvalidFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonCursorTest {

  private static final String INPUT = "test input";
  private static final String TRAILING_CONTENT = "Trailing content";

  @Test
  void testRejectsExcessiveNesting() {
    final String json = "[".repeat(129) + "null" + "]".repeat(129);
    final JsonCursor cursor = new JsonCursor(json, INPUT);

    final InvalidFormatException e = assertThrows(InvalidFormatException.class, cursor::skipValue);

    assertEquals("Malformed test input at offset 128: JSON nesting depth exceeds 128",
        e.getMessage());
  }

  @Test
  void testWalksTheKeysOfAnObject() throws InvalidFormatException {
    final JsonCursor cursor = new JsonCursor("{ \"a\" : 1 , \"b\\u0063\":[2] }", INPUT);
    final List<String> keys = new ArrayList<>();
    final List<String> rawKeys = new ArrayList<>();

    for (String key = cursor.firstKey(); key != null; key = cursor.nextKey("after a field")) {
      keys.add(key);
      rawKeys.add(cursor.rawKey());
      cursor.skipValue();
    }

    assertEquals(List.of("a", "bc"), keys);
    assertEquals(List.of("\"a\"", "\"b\\u0063\""), rawKeys);
    cursor.requireEnd(TRAILING_CONTENT);
  }

  @Test
  void testWalksTheElementsOfAnArray() throws InvalidFormatException {
    final JsonCursor cursor = new JsonCursor("[ 1 , 2,3 ]", INPUT);
    final List<Long> values = new ArrayList<>();

    for (boolean more = cursor.firstElement(); more; more = cursor.nextElement("in an array")) {
      values.add(cursor.parseLong());
    }

    assertEquals(List.of(1L, 2L, 3L), values);
    cursor.requireEnd(TRAILING_CONTENT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{ }", "{\n\t}"})
  void testAnEmptyObjectHasNoKeys(String json) throws InvalidFormatException {
    final JsonCursor cursor = new JsonCursor(json, INPUT);

    assertNull(cursor.firstKey());
    cursor.requireEnd(TRAILING_CONTENT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "[ ]", "[\r\n]"})
  void testAnEmptyArrayHasNoElements(String json) throws InvalidFormatException {
    final JsonCursor cursor = new JsonCursor(json, INPUT);

    assertFalse(cursor.firstElement());
    cursor.requireEnd(TRAILING_CONTENT);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
      "{\"a\":1,}|Malformed test input at offset 7: Trailing comma while skipping an object",
      "{\"a\":1;\"b\":2}|Malformed test input at offset 7: Expected ',' or '}' while skipping an"
          + " object, got ';'",
      "[1,]|Malformed test input at offset 3: Trailing comma while skipping an array",
      "[1 2]|Malformed test input at offset 4: Expected ',' or ']' while skipping an array,"
          + " got '2'",
      "[1\u0663]|Malformed test input at offset 3: Expected ',' or ']' while skipping an array,"
          + " got '\u0663'",
      "\u0663|Malformed test input at offset 0: Unexpected character while skipping a value:"
          + " '\u0663'"})
  void testRejectsMalformedInput(String json, String message) {
    final JsonCursor cursor = new JsonCursor(json, INPUT);

    final InvalidFormatException e = assertThrows(InvalidFormatException.class, cursor::skipValue);

    assertEquals(message, e.getMessage());
  }
}
