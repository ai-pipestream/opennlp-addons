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

package opennlp.tools.artifacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;

/**
 * Adds an {@link #ARTIFACTS} layer by scanning document text with an
 * {@link ArtifactDetector}.
 *
 * <p>No input layer is required. The text is unchanged; callers decide whether to
 * inspect, mask, or repair the reported spans.</p>
 *
 * @since 3.0.0
 */
public class ArtifactAnnotator implements DocumentAnnotator {

  /**
   * Character artifacts, with one {@link TextArtifact} per annotation.
   */
  public static final LayerKey<TextArtifact> ARTIFACTS =
      Layers.key("artifacts", TextArtifact.class);

  private final ArtifactDetector detector;

  /** Initializes the adapter with the built-in {@link CursorArtifactDetector}. */
  public ArtifactAnnotator() {
    this(new CursorArtifactDetector());
  }

  /**
   * Initializes the adapter.
   *
   * @param detector The detector to delegate to. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code detector} is {@code null}.
   */
  public ArtifactAnnotator(ArtifactDetector detector) {
    if (detector == null) {
      throw new IllegalArgumentException("detector must not be null");
    }
    this.detector = detector;
  }

  /**
   * {@inheritDoc} Adds the {@link #ARTIFACTS} layer even when no artifacts are detected.
   *
   * @throws IllegalArgumentException Thrown if {@code document} is {@code null}, or the
   *         detector returns {@code null}, an artifact that is {@code null}, or an artifact
   *         whose span lies outside the text.
   */
  @Override
  public Document annotate(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    final CharSequence text = document.text();
    final List<TextArtifact> detected = detector.detect(text);
    if (detected == null) {
      throw new IllegalArgumentException("detector result must not be null");
    }
    final List<Annotation<TextArtifact>> found = new ArrayList<>(detected.size());
    for (final TextArtifact artifact : detected) {
      if (artifact == null) {
        throw new IllegalArgumentException("detector result must not contain null");
      }
      if (artifact.span().getEnd() > text.length()) {
        throw new IllegalArgumentException("detector artifact spans must lie within the text");
      }
      found.add(new Annotation<>(artifact.span(), artifact));
    }
    return document.with(ARTIFACTS, found);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(ARTIFACTS);
  }
}
