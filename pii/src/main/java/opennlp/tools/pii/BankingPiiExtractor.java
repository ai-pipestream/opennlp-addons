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
 * Extracts ABA routing-number candidates as 9 contiguous ASCII digits.
 *
 * <p>Accepts prefixes {@code 00-12}, {@code 21-32}, {@code 61-72} and {@code 80} from
 * the <a href="https://www.aba.com/news-research/analysis-guides/routing-number-policy-procedures">
 * ABA Routing Number Policy, section IV</a>, and validates the check digit. The
 * {@code 00} prefix includes U.S. government checks. Zero-only values are rejected.</p>
 *
 * <p>A format match does not establish assignment or identify the number's owner.
 * Other numeric identifiers may match, so use the surrounding context to assess results.
 * This detector is opt-in through {@link PiiPacks#payment()} or
 * {@link PiiPacks#allStructured()}, and is not part of the default
 * {@link CursorPiiExtractor}.</p>
 *
 * <p>Spaces and hyphens within a candidate are not accepted. Normalization preserves the
 * detected digits. Instances have no per-call state and may be shared between threads.</p>
 *
 * @since 3.0.0
 */
public final class BankingPiiExtractor implements PiiExtractor {

  private static final int ROUTING_DIGITS = 9;

  /** The repeating weights of the ABA check digit. */
  private static final int[] WEIGHTS = {3, 7, 1};

  private static final int CHECK_MODULUS = 10;

  /** Separators that continue a number when a digit follows: a decimal or a comma group. */
  private static final String NUMBER_CONTINUATIONS = ".,";

  /**
   * Allocated routing-prefix ranges from section IV of the linked ABA policy, inclusive.
   */
  private static final int[][] ALLOCATED_PREFIXES = {{0, 12}, {21, 32}, {61, 72}, {80, 80}};

  /**
   * Initializes an extractor for ABA routing numbers.
   */
  public BankingPiiExtractor() {
  }

  /**
   * {@inheritDoc}
   *
   * <p>Reports one mention per accepted 9-digit run, in text order.</p>
   */
  @Override
  public List<PiiMention> extract(CharSequence text) {
    ParamChecks.requireNonNullArg(text, "text");
    final Boundaries boundaries = new Boundaries(text);
    final Hits hits = new Hits();
    for (int i = 0; i + ROUTING_DIGITS <= text.length(); i++) {
      if (!StringUtil.isAsciiDigit(text.charAt(i)) || !boundaries.onNumberStart(i)) {
        continue;
      }
      final int end = i + ROUTING_DIGITS;
      if (StringUtil.endOfAsciiDigits(text, i) < end
          || !boundaries.onNumberEnd(end, NUMBER_CONTINUATIONS)) {
        continue;
      }
      if (!allocatedPrefix(text, i) || !checkDigitValid(text, i)) {
        continue;
      }
      hits.add(i, end, PiiMention.TYPE_ABA_ROUTING,
          text.subSequence(i, end).toString());
      // The loop increment resumes the scan at the exclusive match end.
      i = end - 1;
    }
    return hits.resolve();
  }

  /**
   * Checks the leading 2 digits in the allocated prefix ranges.
   *
   * @param text The text being scanned.
   * @param start The first digit of the candidate.
   * @return {@code true} if the prefix is allocated by the policy.
   */
  private boolean allocatedPrefix(CharSequence text, int start) {
    final int prefix =
        (text.charAt(start) - '0') * 10 + (text.charAt(start + 1) - '0');
    for (final int[] range : ALLOCATED_PREFIXES) {
      if (prefix >= range[0] && prefix <= range[1]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks the repeating 3, 7, 1 checksum weights and excludes a zero-only candidate.
   *
   * @param text The text being scanned.
   * @param start The first digit of the candidate.
   * @return {@code true} if the weighted sum is a positive multiple of 10.
   */
  private boolean checkDigitValid(CharSequence text, int start) {
    int sum = 0;
    for (int i = 0; i < ROUTING_DIGITS; i++) {
      sum += WEIGHTS[i % WEIGHTS.length] * (text.charAt(start + i) - '0');
    }
    return sum > 0 && sum % CHECK_MODULUS == 0;
  }
}
