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
package opennlp.embeddings.onnx;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.embeddings.spi.TeacherEncoderProvider;
import opennlp.tools.util.ext.ProviderSpec;
import opennlp.tools.util.ext.Providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The capability, availability and registration of the ONNX teacher provider. */
class OnnxTeacherEncoderProviderTest {

  private final OnnxTeacherEncoderProvider provider = new OnnxTeacherEncoderProvider();

  @Test
  void testIdentityAndAvailability() {
    assertEquals("onnx", provider.name());
    assertEquals(0, provider.priority());
    assertTrue(provider.isAvailable(), "onnxruntime is on the test classpath");
  }

  @ParameterizedTest
  @ValueSource(strings = {"model.onnx", "MODEL.ONNX", "onnx/model.Onnx"})
  void testSupportsOnnxFiles(String model) {
    assertTrue(provider.supports(ProviderSpec.of(Path.of(model))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"model.bin", "model.onnx_data", "onnx", "model.safetensors"})
  void testDoesNotSupportOtherFiles(String model) {
    assertFalse(provider.supports(ProviderSpec.of(Path.of(model))));
  }

  @Test
  void testDoesNotSupportOptionsOrASpecWithoutLocation() {
    assertFalse(provider.supports(ProviderSpec.of(Path.of("model.onnx"), Map.of("a", "b"))));
    assertFalse(provider.supports(ProviderSpec.empty()));
  }

  @Test
  void testRejectsANullOrUnsupportedSpec() {
    assertEquals("spec must not be null", assertThrows(IllegalArgumentException.class,
        () -> provider.supports(null)).getMessage());
    assertEquals("spec must not be null", assertThrows(IllegalArgumentException.class,
        () -> provider.create(null)).getMessage());
    final ProviderSpec spec = ProviderSpec.of(Path.of("model.bin"));
    assertEquals("spec is not supported: " + spec, assertThrows(IllegalArgumentException.class,
        () -> provider.create(spec)).getMessage());
  }

  @Test
  void testRegisteredAndSelectedForOnnxModels() {
    final Providers<TeacherEncoderProvider> providers =
        Providers.of(TeacherEncoderProvider.class);
    assertInstanceOf(OnnxTeacherEncoderProvider.class, providers.byName("onnx").orElseThrow());
    assertInstanceOf(OnnxTeacherEncoderProvider.class,
        providers.select(ProviderSpec.of(Path.of("onnx", "model.onnx"))));
  }
}
