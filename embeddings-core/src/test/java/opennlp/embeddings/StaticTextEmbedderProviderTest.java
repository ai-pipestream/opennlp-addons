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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.embeddings.TextEmbedder;
import opennlp.tools.embeddings.TextEmbedderProvider;
import opennlp.tools.util.ext.ProviderSpec;
import opennlp.tools.util.ext.Providers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The capability, availability and registration of the static provider. */
class StaticTextEmbedderProviderTest {

  private final StaticTextEmbedderProvider provider = new StaticTextEmbedderProvider();

  /**
   * {@return the specs the provider does not route to; JUnit requires the source to be static}
   */
  static List<ProviderSpec> unsupportedSpecs() {
    return List.of(ProviderSpec.of(Path.of("model.onnx"), Map.of()),
        ProviderSpec.of(Path.of("model.ONNX"), Map.of()),
        ProviderSpec.of(URI.create("https://example.org/model")),
        ProviderSpec.empty(),
        ProviderSpec.of(Path.of("model"), Map.of("unexpected", "value")));
  }

  @Test
  void testSpecRoutingDoesNotDependOnExistingFiles(@TempDir Path dir) throws IOException {
    final Path model = dir.resolve("missing-model");
    final ProviderSpec spec = ProviderSpec.of(model, Map.of());
    assertTrue(provider.supports(spec));
    assertEquals("Model directory does not exist or is not a directory: " + model,
        assertThrows(IllegalArgumentException.class, () -> provider.create(spec)).getMessage());
    Files.createDirectory(model);
    assertTrue(provider.supports(spec));
  }

  @ParameterizedTest
  @MethodSource("unsupportedSpecs")
  void testUnsupportedSpecsAreNotRoutedAndNotCreated(ProviderSpec spec) {
    assertFalse(provider.supports(spec));
    assertEquals("spec is not supported: " + spec,
        assertThrows(IllegalArgumentException.class, () -> provider.create(spec)).getMessage());
  }

  @Test
  void testNullSpecIsRejected() {
    assertEquals("spec must not be null",
        assertThrows(IllegalArgumentException.class, () -> provider.supports(null)).getMessage());
    assertEquals("spec must not be null",
        assertThrows(IllegalArgumentException.class, () -> provider.create(null)).getMessage());
  }

  @Test
  void testIdentityAndAvailability() {
    assertEquals("static", provider.name());
    assertEquals(0, provider.priority());
    assertTrue(provider.isAvailable());
  }

  /** The provider is registered in this module and is the one selected for a model directory. */
  @Test
  void testRegisteredAndSelectedForModelDirectories(@TempDir Path dir) throws IOException {
    EmbeddingTestFixtures.writeSearchDirectory(dir);
    final Providers<TextEmbedderProvider> providers = Providers.of(TextEmbedderProvider.class);
    final ProviderSpec spec = ProviderSpec.of(dir, Map.of());
    assertInstanceOf(StaticTextEmbedderProvider.class, providers.byName("static").orElseThrow());
    assertInstanceOf(StaticTextEmbedderProvider.class, providers.select(spec));
    final StaticEmbeddingModel expected = StaticEmbeddingModel.load(dir);
    try (TextEmbedder actual = providers.select(spec).create(spec)) {
      assertArrayEquals(expected.embed("king"), actual.embed("king"));
    }
  }
}
