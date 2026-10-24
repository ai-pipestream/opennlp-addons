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

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.LayerKey;
import opennlp.tools.util.ParamChecks;

/**
 * Masks annotated spans from positional document layers, including
 * {@link PiiAnnotator#PII}, entities and custom layers.
 *
 * <p>A masker is configured once with a mask character, a {@link MaskPolicy} or a
 * function that chooses the policy from each annotation's value, and then masks any
 * number of documents. Masking preserves UTF-16 length and other layer offsets. A mask
 * character masks complete spans; a policy can preserve separators or trailing letters
 * and digits. Instances have no per-call state and may be shared between threads.</p>
 *
 * @since 3.0.0
 */
public final class Masker {

  /** The message prefix for a layer that has no spans. */
  private static final String POSITIONAL_LAYER_REQUIRED = "layer must be positional: ";

  /** The message prefix for a layer that the document does not hold. */
  private static final String LAYER_NOT_PRESENT = "layer is not present on the document: ";

  /** The message prefix for a layer whose values the policy function does not accept. */
  private static final String VALUE_TYPE_REQUIRED = "layer values must be of type ";

  /** The annotation value type the policy function accepts, or {@code null} for a fixed policy. */
  private final Class<?> valueType;

  private final Function<Object, MaskPolicy> policies;

  /**
   * Initializes a masker that replaces every character of a span.
   *
   * @param mask The replacement character. Must not be a surrogate.
   * @throws IllegalArgumentException Thrown if {@code mask} is a surrogate.
   */
  public Masker(char mask) {
    this(MaskPolicy.of(mask));
  }

  /**
   * Initializes a masker that applies one policy to every span.
   *
   * @param policy The masking policy. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code policy} is {@code null}.
   */
  public Masker(MaskPolicy policy) {
    ParamChecks.requireNonNullArg(policy, "policy");
    this.valueType = null;
    this.policies = value -> policy;
  }

  /**
   * Initializes a masker that chooses the policy per PII mention.
   *
   * <p>Pass {@link MaskPolicies#byType()} to retain the final 4 card digits and mask
   * access tokens completely. Each policy uses the original span, and retained characters
   * do not restore prior redactions. If different mask characters cover the same position,
   * the last applied mask character is used.</p>
   *
   * @param policies Chooses the policy from a mention. Must not be {@code null} and must
   *                 not return {@code null}.
   * @throws IllegalArgumentException Thrown if {@code policies} is {@code null}.
   */
  public Masker(Function<? super PiiMention, MaskPolicy> policies) {
    this(PiiMention.class, policies);
  }

  /**
   * Initializes a masker that chooses the policy per annotation value of a given type.
   *
   * @param valueType The annotation value type of the layers to mask. Must not be
   *                  {@code null}.
   * @param policies Chooses the policy from an annotation's value. Must not be
   *                 {@code null} and must not return {@code null}.
   * @param <T> The annotation value type.
   * @throws IllegalArgumentException Thrown if {@code valueType} or {@code policies} is
   *         {@code null}.
   */
  public <T> Masker(Class<T> valueType, Function<? super T, MaskPolicy> policies) {
    ParamChecks.requireNonNullArg(valueType, "valueType");
    ParamChecks.requireNonNullArg(policies, "policies");
    this.valueType = valueType;
    this.policies = value -> policies.apply(valueType.cast(value));
  }

  /**
   * Masks the spans of one layer.
   *
   * @param document The document to redact. Must not be {@code null}.
   * @param layer The positional layer to mask. Must not be {@code null} and must be
   *              present on the document.
   * @return The non-null output, with the same UTF-16 length as the document text.
   * @throws IllegalArgumentException Thrown if {@code document} or {@code layer} is
   *         {@code null}, the layer is not present on the document or is document-scoped,
   *         its values are not of the type the policy function accepts, or the policy
   *         function returns {@code null} for an annotation.
   */
  public String mask(Document document, LayerKey<?> layer) {
    ParamChecks.requireNonNullArg(layer, "layer");
    return mask(document, List.of(layer));
  }

  /**
   * Masks the spans of several layers at once.
   *
   * <p>Each span selects positions to redact from the original text. The result masks
   * all selected positions, regardless of layer order. Retaining a trailing character
   * or separator in one span does not restore a redaction made by another span.</p>
   *
   * @param document The document to redact. Must not be {@code null}.
   * @param layers The positional layers to mask. Must not be {@code null} or empty,
   *               and all layers must be non-null and present on the document.
   * @return The non-null output, with the same UTF-16 length as the document text.
   * @throws IllegalArgumentException Thrown if {@code document} or {@code layers} is
   *         {@code null}, {@code layers} is empty or contains {@code null}, a layer is
   *         missing or document-scoped, its values are not of the type the policy function
   *         accepts, or the policy function returns {@code null} for an annotation.
   */
  public String mask(Document document, Collection<LayerKey<?>> layers) {
    ParamChecks.requireNonNullArg(document, "document");
    ParamChecks.requireNonEmpty(layers, "layers");
    for (final LayerKey<?> layer : layers) {
      if (layer == null) {
        throw new IllegalArgumentException("layers must not contain null");
      }
      if (!document.layers().contains(layer)) {
        throw new IllegalArgumentException(LAYER_NOT_PRESENT + layer);
      }
      if (layer.scope() != LayerKey.Scope.POSITIONAL) {
        throw new IllegalArgumentException(POSITIONAL_LAYER_REQUIRED + layer);
      }
      if (valueType != null && !valueType.isAssignableFrom(layer.type())) {
        throw new IllegalArgumentException(VALUE_TYPE_REQUIRED + valueType.getName() + ": " + layer);
      }
    }
    final String text = document.text().toString();
    final StringBuilder masked = new StringBuilder(text);
    for (final LayerKey<?> layer : layers) {
      for (final Annotation<?> annotation : document.get(layer)) {
        final MaskPolicy policy = policies.apply(annotation.value());
        if (policy == null) {
          throw new IllegalArgumentException("policies must not return null");
        }
        final int start = annotation.span().getStart();
        final int end = annotation.span().getEnd();
        policy.apply(text.substring(start, end), masked, start);
      }
    }
    return masked.toString();
  }
}
