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
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the document adapter around the built-in detector. */
public class AssetAnnotatorTest {

  @Test
  void testProvidesTheAssetsLayer() {
    final AssetAnnotator annotator = new AssetAnnotator();
    assertEquals(Set.of(AssetAnnotator.ASSETS), annotator.provides());
    assertEquals("opennlp:assets", AssetAnnotator.ASSETS.id());
  }

  @Test
  void testAnnotatesDetectedAssets() {
    final byte[] pdf = new byte[32];
    final byte[] magic = {'%', 'P', 'D', 'F', '-'};
    System.arraycopy(magic, 0, pdf, 0, magic.length);
    final String encoded = Base64.getEncoder().encodeToString(pdf);
    final Document document = new AssetAnnotator()
        .annotate(Document.of("attached: " + encoded));
    final List<Annotation<EmbeddedAsset>> assets =
        document.get(AssetAnnotator.ASSETS);
    assertEquals(1, assets.size());
    assertEquals(EmbeddedAsset.FORMAT_PDF, assets.get(0).value().format());
  }

  @Test
  void testCleanDocumentGetsAnEmptyLayer() {
    final Document document = new AssetAnnotator().annotate(Document.of("no assets"));
    assertTrue(document.layers().contains(AssetAnnotator.ASSETS));
    assertTrue(document.get(AssetAnnotator.ASSETS).isEmpty());
  }

  @Test
  void testRejectsContractViolations() {
    assertThrows(IllegalArgumentException.class, () -> new AssetAnnotator(null));
    assertThrows(IllegalArgumentException.class,
        () -> new AssetAnnotator().annotate(null));
  }

  /**
   * The results a custom detector returns are checked before they become a layer: a
   * missing list, a missing asset and an asset outside the text are rejected with the
   * documented messages instead of failing later with another exception.
   *
   * @param message The expected message.
   * @param detector The misbehaving detector.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("misbehavingDetectors")
  void testRejectsMisbehavingDetectorResults(String message, AssetDetector detector) {
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> new AssetAnnotator(detector).annotate(Document.of("short text")));
    assertEquals(message, e.getMessage());
  }

  /** @return Detector results that violate the {@link AssetDetector} contract. */
  private static Stream<Arguments> misbehavingDetectors() {
    final EmbeddedAsset outside = new EmbeddedAsset(new Span(4, 20), new Span(4, 20), "png",
        "image/png", 12, -1, -1);
    final List<EmbeddedAsset> holdsNull = new ArrayList<>();
    holdsNull.add(null);
    return Stream.of(
        Arguments.of("detector result must not be null", (AssetDetector) text -> null),
        Arguments.of("detector result must not contain null", (AssetDetector) text -> holdsNull),
        Arguments.of("detector asset spans must lie within the text",
            (AssetDetector) text -> List.of(outside)));
  }
}
