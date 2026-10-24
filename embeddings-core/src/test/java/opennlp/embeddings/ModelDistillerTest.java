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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Argument checks of {@link ModelDistiller} that need no teacher model. */
class ModelDistillerTest {

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void testRejectsANonPositivePcaDimension(int pcaDims) {
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> new ModelDistiller(pcaDims, null));
    assertEquals("pcaDims must be positive", e.getMessage());
  }

  @Test
  void testRejectsNullEncoderProviders() {
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> new ModelDistiller(1, null, null));
    assertEquals("encoders must not be null", e.getMessage());
  }
}
