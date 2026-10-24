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

/**
 * Writes JSON string literals for the files this package generates. The escapes are the ones
 * RFC 8259 defines: a quotation mark and a reverse solidus are escaped, the control characters
 * with a two-character escape use it, and the other control characters become {@code \}{@code
 * u00XX}. Every other character, supplementary ones included, is written as it is.
 */
final class JsonStrings {

  /** The lower-case hexadecimal digits of a {@code \}{@code u00XX} escape. */
  private static final String HEX_DIGITS = "0123456789abcdef";

  /** The last control character, below the space. */
  private static final char LAST_CONTROL = 0x1F;

  /** Not instantiable. */
  private JsonStrings() {
  }

  /**
   * {@return {@code value} as a JSON string literal, quoted and escaped}
   *
   * @param value The text to quote. Must not be {@code null}.
   */
  static String quote(CharSequence value) {
    final StringBuilder json = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\b' -> json.append("\\b");
        case '\f' -> json.append("\\f");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> {
          if (c <= LAST_CONTROL) {
            json.append("\\u00").append(HEX_DIGITS.charAt(c >>> 4))
                .append(HEX_DIGITS.charAt(c & 0x0F));
          } else {
            json.append(c);
          }
        }
      }
    }
    return json.append('"').toString();
  }
}
