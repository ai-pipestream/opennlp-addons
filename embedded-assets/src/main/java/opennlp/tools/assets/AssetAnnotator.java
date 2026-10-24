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

package opennlp.tools.assets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.util.ParamChecks;

/**
 * Adds an {@link #ASSETS} layer by scanning the document text with an
 * {@link AssetDetector}.
 *
 * <p>No input layer is required. The text is unchanged; {@link AssetFolder} can
 * replace payloads with text while preserving their original offsets.</p>
 *
 * @since 3.0.0
 */
public final class AssetAnnotator implements DocumentAnnotator {

  /**
   * Embedded binary assets; each annotation covers one asset and carries its
   * {@link EmbeddedAsset}.
   */
  public static final LayerKey<EmbeddedAsset> ASSETS =
      Layers.key("assets", EmbeddedAsset.class);

  private final AssetDetector detector;

  /** Initializes the adapter with the built-in {@link CursorAssetDetector}. */
  public AssetAnnotator() {
    this(new CursorAssetDetector());
  }

  /**
   * Initializes the adapter.
   *
   * @param detector The detector to delegate to. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code detector} is {@code null}.
   */
  public AssetAnnotator(AssetDetector detector) {
    this.detector = ParamChecks.requireNonNullArg(detector, "detector");
  }

  /**
   * {@inheritDoc} Adds the {@link #ASSETS} layer even when no assets are detected.
   *
   * @throws IllegalArgumentException Thrown if {@code document} is {@code null}, or the
   *         detector returns {@code null}, an asset that is {@code null}, or an asset
   *         whose span lies outside the text.
   */
  @Override
  public Document annotate(Document document) {
    ParamChecks.requireNonNullArg(document, "document");
    final CharSequence text = document.text();
    final List<EmbeddedAsset> detected = detector.detect(text);
    if (detected == null) {
      throw new IllegalArgumentException("detector result must not be null");
    }
    final List<Annotation<EmbeddedAsset>> found = new ArrayList<>(detected.size());
    for (final EmbeddedAsset asset : detected) {
      if (asset == null) {
        throw new IllegalArgumentException("detector result must not contain null");
      }
      if (asset.span().getEnd() > text.length()) {
        throw new IllegalArgumentException("detector asset spans must lie within the text");
      }
      found.add(new Annotation<>(asset.span(), asset));
    }
    return document.with(ASSETS, found);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(ASSETS);
  }
}
