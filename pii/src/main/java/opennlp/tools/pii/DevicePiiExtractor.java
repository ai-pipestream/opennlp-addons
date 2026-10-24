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

import java.util.List;

import opennlp.tools.util.ParamChecks;
import opennlp.tools.util.StringUtil;

/**
 * Extracts labeled International Mobile Equipment Identity (IMEI) candidates.
 *
 * <p>An IMEI is reported only when an ASCII case-insensitive {@code IMEI} label directly
 * precedes it, separated only by whitespace or {@code :}, {@code #}, or {@code =}. The
 * label must not continue a Unicode letter or digit. The candidate must contain
 * 15 ASCII digits and pass the Luhn check specified by
 * <a href="https://imeidb.gsma.com/imei/resources/documents/TS.06-v22.0.pdf">
 * GSMA TS.06</a>. A zero-only value, the placeholder of a device without an IMEI, is
 * rejected. Single spaces or hyphens may separate digit groups, but may not be
 * mixed. Any Unicode space separator, such as a no-break space, counts as a space.</p>
 *
 * <p>This detector is opt-in. Format and checksum validation do not establish that an
 * identifier was assigned to a device. Normalization removes numeric separators.</p>
 *
 * <p>Instances have no per-call state and may be shared between threads.</p>
 *
 * @since 3.0.0
 */
public final class DevicePiiExtractor implements PiiExtractor {

  private static final String[] LABELS = {"imei"};

  /** Punctuation that may separate the label and the value. */
  private static final String LABEL_PUNCTUATION = ":#=";

  /** Separators that continue the digit groups when a digit follows. */
  private static final String GROUP_CONTINUATIONS = "- ";

  private static final int DIGITS = 15;

  /** Initializes an extractor for IMEIs. */
  public DevicePiiExtractor() {
  }

  /**
   * {@inheritDoc}
   *
   * <p>Reports one mention per labeled, checksum-valid IMEI, in text order.</p>
   */
  @Override
  public List<PiiMention> extract(CharSequence text) {
    ParamChecks.requireNonNullArg(text, "text");
    final Hits hits = new Hits();
    for (int i = 0; i < text.length(); i++) {
      if (!StringUtil.isAsciiDigit(text.charAt(i)) || !Boundaries.onNumberStart(text, i)
          || !Boundaries.precededByLabel(text, i, LABELS, LABEL_PUNCTUATION)) {
        continue;
      }
      final StringBuilder normalized = new StringBuilder(DIGITS);
      final int end = readDigits(text, i, normalized);
      if (end < 0 || !Luhn.valid(normalized, DIGITS)) {
        continue;
      }
      hits.add(i, end, PiiMention.TYPE_IMEI, normalized.toString());
      i = end - 1;
    }
    return hits.resolve();
  }

  /**
   * Parses 15 digits with optional consistent separators.
   *
   * @param text The text being scanned.
   * @param start The first digit.
   * @param normalized The digit collector.
   * @return The exclusive candidate end, or {@code -1} if the form is invalid.
   */
  private int readDigits(CharSequence text, int start, StringBuilder normalized) {
    int p = start;
    char separator = 0;
    while (p < text.length() && normalized.length() < DIGITS) {
      final char c = Boundaries.separatorKind(text.charAt(p));
      if (StringUtil.isAsciiDigit(c)) {
        normalized.append(c);
        p++;
      } else if ((c == '-' || c == ' ') && p + 1 < text.length()
          && StringUtil.isAsciiDigit(text.charAt(p + 1))) {
        if (separator != 0 && separator != c) {
          return -1;
        }
        separator = c;
        p++;
      } else {
        break;
      }
    }
    if (normalized.length() != DIGITS
        || !Boundaries.onNumberEnd(text, p, GROUP_CONTINUATIONS)) {
      return -1;
    }
    return p;
  }
}
