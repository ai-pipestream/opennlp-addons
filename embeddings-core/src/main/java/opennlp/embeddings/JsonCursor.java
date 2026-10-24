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

import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.StringUtil;

/**
 * Cursor shared by the small JSON readers in this package. It parses scalar values and can skip
 * one value of any type. Each reader handles its expected input structure. Malformed input raises
 * an {@link InvalidFormatException} that includes the input name and offset.
 */
final class JsonCursor {

  private static final int MAX_NESTING_DEPTH = 128;

  private final String text;
  private final String inputName;
  private int position;
  private int keyStart;
  private int keyEnd;

  /**
   * Creates a cursor positioned at the start of the given JSON text.
   *
   * @param text      The JSON text to scan. Must not be {@code null}.
   * @param inputName What the text is (for error messages), e.g. {@code "safetensors header"}
   *                  or a file name.
   */
  JsonCursor(String text, String inputName) {
    this.text = text;
    this.inputName = inputName;
  }

  /**
   * Advances the cursor past any run of whitespace. JSON (RFC 8259) defines insignificant
   * whitespace as exactly space, tab, line feed and carriage return, so this scan is ASCII on
   * purpose; other Unicode whitespace outside a string is malformed JSON.
   */
  void skipWhitespace() {
    while (position < text.length()) {
      final char c = text.charAt(position);
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
        return;
      }
      position++;
    }
  }

  /** {@return the cursor's current offset into the text, for readers that capture raw spans} */
  int position() {
    return position;
  }

  /**
   * {@return the character at the cursor without advancing}
   *
   * @throws InvalidFormatException Thrown if the cursor is at the end of the input.
   */
  char peek() throws InvalidFormatException {
    if (position >= text.length()) {
      throw malformed("Unexpected end of input");
    }
    return text.charAt(position);
  }

  /**
   * {@return the character at the cursor, advancing past it}
   *
   * @throws InvalidFormatException Thrown if the cursor is at the end of the input.
   */
  char consume() throws InvalidFormatException {
    final char c = peek();
    position++;
    return c;
  }

  /**
   * Consumes the next character, requiring it to be {@code c}.
   *
   * @param c The expected character.
   * @throws InvalidFormatException Thrown if the next character is not {@code c}.
   */
  void expect(char c) throws InvalidFormatException {
    final char actual = consume();
    if (actual != c) {
      throw malformed("Expected '" + c + "', got '" + actual + "'");
    }
  }

  /**
   * Consumes the given literal (for example {@code "true"}) when it starts at the cursor,
   * leaving the cursor untouched when it does not.
   *
   * @param literal The literal to match.
   * @return {@code true} when the literal was consumed.
   */
  boolean consumeLiteral(String literal) {
    if (text.startsWith(literal, position)) {
      position += literal.length();
      return true;
    }
    return false;
  }

  /**
   * Requires the rest of the input to be whitespace only.
   *
   * @param message What to report when other content follows.
   * @throws InvalidFormatException Thrown if non-whitespace content follows the cursor.
   */
  void requireEnd(String message) throws InvalidFormatException {
    skipWhitespace();
    if (position < text.length()) {
      throw malformed(message);
    }
  }

  /**
   * {@return the JSON string starting at the cursor, with escapes decoded}
   *
   * @throws InvalidFormatException Thrown if the string is unterminated or has a bad escape.
   */
  String parseString() throws InvalidFormatException {
    expect('"');
    final StringBuilder value = new StringBuilder();
    while (true) {
      if (position >= text.length()) {
        throw malformed("Unterminated string");
      }
      final char c = text.charAt(position++);
      if (c == '"') {
        return value.toString();
      }
      if (c == '\\') {
        value.append(parseEscape());
      } else if (c <= 0x1F) {
        throw malformed("Unescaped control character in a string");
      } else {
        value.append(c);
      }
    }
  }

  /** {@return the character named by the escape sequence following a backslash} */
  private char parseEscape() throws InvalidFormatException {
    if (position >= text.length()) {
      throw malformed("Unterminated escape sequence");
    }
    final char escape = text.charAt(position++);
    return switch (escape) {
      case '"' -> '"';
      case '\\' -> '\\';
      case '/' -> '/';
      case 'b' -> '\b';
      case 'f' -> '\f';
      case 'n' -> '\n';
      case 'r' -> '\r';
      case 't' -> '\t';
      case 'u' -> parseUnicodeEscape();
      default -> throw malformed("Unknown escape sequence: \\" + escape);
    };
  }

  /** {@return the character named by a {@code \\uXXXX} escape} */
  private char parseUnicodeEscape() throws InvalidFormatException {
    if (position + 4 > text.length()) {
      throw malformed("Truncated \\u escape sequence");
    }
    final String hex = text.substring(position, position + 4);
    position += 4;
    // JSON escape digits are limited to the ASCII hexadecimal characters.
    int value = 0;
    for (int i = 0; i < 4; i++) {
      final int digit = hexadecimalValue(hex.charAt(i));
      if (digit < 0) {
        throw malformed("Malformed \\u escape sequence: " + hex);
      }
      value = (value << 4) | digit;
    }
    return (char) value;
  }

  /** {@return the value of an ASCII hexadecimal digit, or {@code -1} for another character} */
  private int hexadecimalValue(char c) {
    if (c >= '0' && c <= '9') {
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
   * Skips one JSON number, holding it to the grammar (optional minus, digits, optional fraction,
   * optional signed exponent). This validation also applies to skipped fields.
   */
  private void skipNumber() throws InvalidFormatException {
    skipIntegerPart();
    if (position < text.length() && text.charAt(position) == '.') {
      position++;
      if (position >= text.length() || !StringUtil.isAsciiDigit(text.charAt(position))) {
        throw malformed("Malformed number: digit expected after the decimal point");
      }
      while (position < text.length() && StringUtil.isAsciiDigit(text.charAt(position))) {
        position++;
      }
    }
    if (position < text.length()
        && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
      position++;
      if (position < text.length()
          && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
        position++;
      }
      if (position >= text.length() || !StringUtil.isAsciiDigit(text.charAt(position))) {
        throw malformed("Malformed number: digit expected in the exponent");
      }
      while (position < text.length() && StringUtil.isAsciiDigit(text.charAt(position))) {
        position++;
      }
    }
  }

  /**
   * Skips the optional sign and integer part of a JSON number.
   *
   * @throws InvalidFormatException Thrown if the integer part is absent or has a leading zero.
   */
  private void skipIntegerPart() throws InvalidFormatException {
    if (peek() == '-') {
      position++;
    }
    if (position >= text.length() || !StringUtil.isAsciiDigit(text.charAt(position))) {
      throw malformed("Malformed number");
    }
    if (text.charAt(position) == '0') {
      position++;
      if (position < text.length() && StringUtil.isAsciiDigit(text.charAt(position))) {
        throw malformed("Malformed number: leading zeros are not allowed");
      }
      return;
    }
    while (position < text.length() && StringUtil.isAsciiDigit(text.charAt(position))) {
      position++;
    }
  }

  /**
   * {@return the integer starting at the cursor, parsed as a {@code long}}
   *
   * @throws InvalidFormatException Thrown if no integer is present or it overflows a long.
   */
  long parseLong() throws InvalidFormatException {
    final int start = position;
    skipIntegerPart();
    try {
      return Long.parseLong(text.substring(start, position));
    } catch (NumberFormatException e) {
      throw malformed("Malformed integer: " + text.substring(start, position));
    }
  }

  /**
   * {@return the finite JSON number starting at the cursor, parsed as a {@code double}}
   *
   * @throws InvalidFormatException Thrown if no JSON number is present or its value is not
   *     finite.
   */
  double parseDouble() throws InvalidFormatException {
    final int start = position;
    skipNumber();
    final String number = text.substring(start, position);
    try {
      final double value = Double.parseDouble(number);
      if (!Double.isFinite(value)) {
        throw malformed("Number is not finite: " + number);
      }
      return value;
    } catch (NumberFormatException e) {
      throw malformed("Malformed number: " + number);
    }
  }

  /**
   * {@return the JSON boolean starting at the cursor}
   *
   * @throws InvalidFormatException Thrown if the next value is not {@code true} or
   *     {@code false}.
   */
  boolean parseBoolean() throws InvalidFormatException {
    if (consumeLiteral("true")) {
      return true;
    }
    if (consumeLiteral("false")) {
      return false;
    }
    throw malformed("Expected a boolean");
  }

  /**
   * Opens the object at the cursor and reads its first key, leaving the cursor at the first value.
   * A reader walks an object with {@code for (String key = firstKey(); key != null;
   * key = nextKey(...))}, reading or skipping one value per key.
   *
   * @return The first key, or {@code null} when the object is empty; its closing brace is then
   *     consumed.
   * @throws InvalidFormatException Thrown if no object starts at the cursor or its first key is
   *     malformed.
   */
  String firstKey() throws InvalidFormatException {
    expect('{');
    skipWhitespace();
    if (peek() == '}') {
      position++;
      return null;
    }
    return key();
  }

  /**
   * Reads the separator after an object value and the key that follows it, leaving the cursor at
   * the next value.
   *
   * @param where Where the separator is expected, for the message, for example
   *              {@code "after a model field"}.
   * @return The next key, or {@code null} when the object ends; its closing brace is then
   *     consumed.
   * @throws InvalidFormatException Thrown if neither {@code ,} nor <code>}</code> follows the
   *     value, if a comma is followed by the closing brace, or if the next key is malformed.
   */
  String nextKey(String where) throws InvalidFormatException {
    skipWhitespace();
    final char next = consume();
    if (next == ',') {
      skipWhitespace();
      if (peek() == '}') {
        throw malformed("Trailing comma " + where);
      }
      return key();
    }
    if (next == '}') {
      return null;
    }
    throw malformed("Expected ',' or '}' " + where + ", got '" + next + "'");
  }

  /** {@return the object key at the cursor, leaving the cursor at its value} */
  private String key() throws InvalidFormatException {
    skipWhitespace();
    keyStart = position;
    final String key = parseString();
    keyEnd = position;
    skipWhitespace();
    expect(':');
    skipWhitespace();
    return key;
  }

  /**
   * {@return the last key read by {@link #firstKey()} or {@link #nextKey(String)} as it is
   * written in the text, quotes and escapes included, for a reader that copies it unchanged}
   */
  String rawKey() {
    return text.substring(keyStart, keyEnd);
  }

  /**
   * Opens the array at the cursor, leaving the cursor at its first element. A reader walks an
   * array with {@code for (boolean more = firstElement(); more; more = nextElement(...))}.
   *
   * @return Whether the array has an element; for an empty array its closing bracket is consumed.
   * @throws InvalidFormatException Thrown if no array starts at the cursor.
   */
  boolean firstElement() throws InvalidFormatException {
    expect('[');
    skipWhitespace();
    if (peek() == ']') {
      position++;
      return false;
    }
    return true;
  }

  /**
   * Reads the separator after an array element, leaving the cursor at the next element.
   *
   * @param where Where the separator is expected, for the message, for example
   *              {@code "in a number array"}.
   * @return Whether another element follows; at the end of the array its closing bracket is
   *     consumed.
   * @throws InvalidFormatException Thrown if neither {@code ,} nor {@code ]} follows the element,
   *     or if a comma is followed by the closing bracket.
   */
  boolean nextElement(String where) throws InvalidFormatException {
    skipWhitespace();
    final char next = consume();
    if (next == ',') {
      skipWhitespace();
      if (peek() == ']') {
        throw malformed("Trailing comma " + where);
      }
      return true;
    }
    if (next == ']') {
      return false;
    }
    throw malformed("Expected ',' or ']' " + where + ", got '" + next + "'");
  }

  /**
   * Skips one JSON value of any type (string, number, array, object, true/false/null), allowing a
   * reader to ignore unknown fields.
   */
  void skipValue() throws InvalidFormatException {
    skipValue(0);
  }

  /**
   * Skips one JSON value at the given container depth.
   *
   * @param depth The number of enclosing arrays and objects.
   * @throws InvalidFormatException Thrown if the value is malformed or nested too deeply.
   */
  private void skipValue(int depth) throws InvalidFormatException {
    skipWhitespace();
    final char c = peek();
    if (c == '"') {
      parseString();
    } else if (c == '[') {
      requireContainerDepth(depth);
      for (boolean more = firstElement(); more; more = nextElement("while skipping an array")) {
        skipValue(depth + 1);
      }
    } else if (c == '{') {
      requireContainerDepth(depth);
      for (String key = firstKey(); key != null; key = nextKey("while skipping an object")) {
        skipValue(depth + 1);
      }
    } else if (c == '-' || StringUtil.isAsciiDigit(c)) {
      skipNumber();
    } else if (consumeLiteral("true") || consumeLiteral("false") || consumeLiteral("null")) {
      // consumed, nothing to record
    } else {
      throw malformed("Unexpected character while skipping a value: '" + c + "'");
    }
  }

  /**
   * Rejects a container whose contents would exceed the nesting limit.
   *
   * @param depth The number of enclosing arrays and objects.
   * @throws InvalidFormatException Thrown at the nesting limit.
   */
  private void requireContainerDepth(int depth) throws InvalidFormatException {
    if (depth >= MAX_NESTING_DEPTH) {
      throw malformed("JSON nesting depth exceeds " + MAX_NESTING_DEPTH);
    }
  }

  /**
   * {@return an exception naming the input and the cursor offset}
   *
   * @param message What was wrong at the cursor.
   */
  InvalidFormatException malformed(String message) {
    return new InvalidFormatException(
        "Malformed " + inputName + " at offset " + position + ": " + message);
  }
}
