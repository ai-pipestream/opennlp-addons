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

/**
 * Runs one delegate extractor and validates its output before composition or annotation.
 *
 * <p>Instances have no per-call state and may be shared between threads.</p>
 */
final class PiiExtraction {

  private final PiiExtractor extractor;

  /**
   * Initializes the validated view of a delegate.
   *
   * @param extractor The non-null delegate.
   */
  PiiExtraction(PiiExtractor extractor) {
    this.extractor = extractor;
  }

  /**
   * Runs the delegate and validates mention presence and text bounds.
   *
   * @param text The non-null input text.
   * @return The validated delegate result, without reordering or filtering.
   * @throws IllegalArgumentException Thrown if the result or a mention is null, or a
   *         mention lies outside the input text.
   */
  List<PiiMention> extract(CharSequence text) {
    final List<PiiMention> mentions = extractor.extract(text);
    if (mentions == null) {
      throw new IllegalArgumentException("extractor result must not be null");
    }
    final int length = text.length();
    for (final PiiMention mention : mentions) {
      if (mention == null) {
        throw new IllegalArgumentException("extractor result must not contain null");
      }
      if (mention.span().getStart() < 0 || mention.span().getEnd() > length) {
        throw new IllegalArgumentException("extractor mention offsets must lie within the input text");
      }
    }
    return mentions;
  }
}
