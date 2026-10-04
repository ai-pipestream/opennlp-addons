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
package opennlp.addons.eval;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvalDataTest {

  private static final Path HOME = Path.of("/home/eval");

  private static UnaryOperator<String> lookup(Map<String, String> values) {
    return values::get;
  }

  @Test
  void testDefaultsUnderTheDownloadHome() {
    final EvalData data = EvalData.resolve(lookup(Map.of("user.home", HOME.toString())),
        lookup(Map.of()));
    assertEquals(HOME.resolve(".opennlp"), data.models());
    assertEquals(HOME.resolve(".opennlp").resolve("eval"), data.root());
  }

  @Test
  void testDownloadHomePropertyMovesBothDirectories() {
    final EvalData data = EvalData.resolve(lookup(Map.of("user.home", HOME.toString(),
        EvalData.DOWNLOAD_HOME, "/srv/nlp")), lookup(Map.of(EvalData.DOWNLOAD_HOME, "/ignored")));
    assertEquals(Path.of("/srv/nlp/.opennlp"), data.models());
    assertEquals(Path.of("/srv/nlp/.opennlp/eval"), data.root());
  }

  @Test
  void testDownloadHomeVariableWhenThePropertyIsUnset() {
    final EvalData data = EvalData.resolve(lookup(Map.of("user.home", HOME.toString())),
        lookup(Map.of(EvalData.DOWNLOAD_HOME, "/srv/nlp")));
    assertEquals(Path.of("/srv/nlp/.opennlp"), data.models());
    assertEquals(Path.of("/srv/nlp/.opennlp/eval"), data.root());
  }

  @Test
  void testEvalDirectoryPropertyWinsOverTheVariable() {
    final EvalData data = EvalData.resolve(lookup(Map.of("user.home", HOME.toString(),
        EvalData.DIRECTORY_PROPERTY, "/data/prop")),
        lookup(Map.of(EvalData.DIRECTORY_VARIABLE, "/data/env")));
    assertEquals(Path.of("/data/prop"), data.root());
    assertEquals(HOME.resolve(".opennlp"), data.models());
  }

  @Test
  void testEvalDirectoryVariableWhenThePropertyIsUnset() {
    final EvalData data = EvalData.resolve(lookup(Map.of("user.home", HOME.toString())),
        lookup(Map.of(EvalData.DIRECTORY_VARIABLE, "/data/env")));
    assertEquals(Path.of("/data/env"), data.root());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", " ", "　", " ", " \t "})
  void testBlankSettingsCountAsUnset(String blank) {
    final EvalData data = EvalData.resolve(lookup(Map.of("user.home", HOME.toString(),
        EvalData.DIRECTORY_PROPERTY, blank, EvalData.DOWNLOAD_HOME, blank)),
        lookup(Map.of(EvalData.DIRECTORY_VARIABLE, blank, EvalData.DOWNLOAD_HOME, blank)));
    assertEquals(HOME.resolve(".opennlp").resolve("eval"), data.root());
    assertEquals(HOME.resolve(".opennlp"), data.models());
  }

  @Test
  void testResolveRejectsNullLookups() {
    final UnaryOperator<String> empty = lookup(Map.of());
    assertThrows(IllegalArgumentException.class, () -> EvalData.resolve(null, empty));
    assertThrows(IllegalArgumentException.class, () -> EvalData.resolve(empty, null));
  }

  @Test
  void testResolveRequiresAUserHome() {
    final UnaryOperator<String> empty = lookup(Map.of());
    assertThrows(IllegalStateException.class, () -> EvalData.resolve(empty, empty));
  }

  @Test
  void testConstructorRejectsNulls() {
    assertThrows(IllegalArgumentException.class, () -> new EvalData(null, HOME));
    assertThrows(IllegalArgumentException.class, () -> new EvalData(HOME, null));
  }

  @Test
  void testDatasetAndModelResolveUnderTheirRoots() {
    final EvalData data = new EvalData(Path.of("/data"), Path.of("/models"));
    assertEquals(Path.of("/data/ud"), data.dataset("ud"));
    assertEquals(Path.of("/models/en-chunker.bin"), data.model("en-chunker.bin"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", " ", "　", ".", "..", "a/b", "a\\b", "/abs"})
  void testDatasetRejectsBlankAndNestedNames(String name) {
    final EvalData data = new EvalData(Path.of("/data"), Path.of("/models"));
    assertThrows(IllegalArgumentException.class, () -> data.dataset(name));
    assertThrows(IllegalArgumentException.class, () -> data.model(name));
  }

  @Test
  void testDatasetRejectsNull() {
    final EvalData data = new EvalData(Path.of("/data"), Path.of("/models"));
    assertThrows(IllegalArgumentException.class, () -> data.dataset(null));
    assertThrows(IllegalArgumentException.class, () -> data.model(null));
  }

  @Test
  void testFromEnvironmentUsesTheJvmSettings() {
    final EvalData data = EvalData.fromEnvironment();
    final String property = System.getProperty(EvalData.DIRECTORY_PROPERTY);
    if (property != null && !property.isBlank()) {
      assertEquals(Path.of(property), data.root());
    }
    assertEquals(".opennlp", data.models().getFileName().toString());
  }
}
