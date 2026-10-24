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

import java.util.ArrayList;
import java.util.List;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.ParamChecks;

/**
 * Reads validated mentions from a document's {@link PiiAnnotator#PII} layer.
 */
final class PiiLayer {

  private final Document document;

  /**
   * Initializes the reader of one document's PII layer.
   *
   * @param document The document. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code document} is null.
   */
  PiiLayer(Document document) {
    ParamChecks.requireNonNullArg(document, "document");
    this.document = document;
  }

  /**
   * Retrieves the PII mentions of the document.
   *
   * @return The mentions in annotation order.
   * @throws IllegalArgumentException Thrown if the document lacks the PII layer or
   *         contains a mention with offsets that do not match the annotation.
   */
  List<PiiMention> mentions() {
    if (!document.layers().contains(PiiAnnotator.PII)) {
      throw new IllegalArgumentException("document does not carry the "
          + PiiAnnotator.PII.id() + " layer");
    }
    final List<Annotation<PiiMention>> annotations = document.get(PiiAnnotator.PII);
    final List<PiiMention> mentions = new ArrayList<>(annotations.size());
    for (final Annotation<PiiMention> annotation : annotations) {
      final PiiMention mention = annotation.value();
      if (annotation.span().getStart() != mention.span().getStart()
          || annotation.span().getEnd() != mention.span().getEnd()) {
        throw new IllegalArgumentException("PII mention offsets must match annotation offsets");
      }
      mentions.add(mention);
    }
    return mentions;
  }
}
