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
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import opennlp.embeddings.spi.TeacherEncoder;
import opennlp.embeddings.spi.TeacherEncoderProvider;
import opennlp.tools.embeddings.EmbeddingException;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.ext.ProviderSpec;
import opennlp.tools.util.ext.Providers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates graph inputs and pooled output using small ONNX models.
 */
class OnnxTeacherEncoderTest {

  /**
   * Requires a stable vector length across batches in one session.
   *
   * @param initialSize The initial batch size and vector length.
   * @param changedSize The next batch size and vector length.
   * @param directory The test directory.
   * @throws Exception Thrown if the test graph cannot be written or loaded.
   */
  @ParameterizedTest
  @CsvSource({"1, 3", "3, 1"})
  void testRejectsChangingHiddenDimension(int initialSize, int changedSize,
                                         @TempDir Path directory) throws Exception {
    final Path model = EmbeddingTestFixtures.writeVariableDimensionOnnxModel(directory);
    try (TeacherEncoder encoder = open(model)) {
      assertEquals(initialSize, encoder.encodeBatch(new long[initialSize][1])[0].length);

      final EmbeddingException error = assertThrows(EmbeddingException.class,
          () -> encoder.encodeBatch(new long[changedSize][1]));
      assertEquals("ONNX output 'last_hidden_state' has hidden dimension " + changedSize
          + ", expected " + initialSize + " from the initial batch", error.getMessage());
      assertEquals(initialSize, encoder.encodeBatch(new long[initialSize][1])[0].length);
    }
  }

  @Test
  void testRejectsMissingFile(@TempDir Path directory) {
    final Path missing = directory.resolve("model.onnx");
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> open(missing));
    assertEquals("File does not exist or is not a regular file: " + missing, e.getMessage());
  }

  @Test
  void testDirectoryIsNotARegularFile(@TempDir Path directory) throws IOException {
    final Path model = Files.createDirectory(directory.resolve("model.onnx"));
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> open(model));
    assertEquals("File does not exist or is not a regular file: " + model, e.getMessage());
  }

  @Test
  void testRejectsNullAndEmptySequences(@TempDir Path directory) throws Exception {
    final Path model = EmbeddingTestFixtures.writeTinyOnnxModel(directory);

    try (TeacherEncoder encoder = open(model)) {
      assertEquals("batch[0] must not be null", assertThrows(IllegalArgumentException.class,
          () -> encoder.encodeBatch(new long[][] {null})).getMessage());
      assertEquals("batch[1] must not be null", assertThrows(IllegalArgumentException.class,
          () -> encoder.encodeBatch(new long[][] {{1}, null})).getMessage());
      assertEquals("batch[0] must not be empty", assertThrows(IllegalArgumentException.class,
          () -> encoder.encodeBatch(new long[][] {new long[0]})).getMessage());
    }
  }

  @Test
  void testSupportsGraphWithoutAttentionMask(@TempDir Path directory) throws Exception {
    final Path model = EmbeddingTestFixtures.writeInputIdsOnlyOnnxModel(directory);

    try (TeacherEncoder encoder = open(model)) {
      assertArrayEquals(new float[] {1f, -2f, 4f}, encoder.encodeBatch(new long[][] {{2}})[0]);
    }
  }

  @Test
  void testRejectsUnsupportedInput(@TempDir Path directory) throws Exception {
    final Path model = EmbeddingTestFixtures.writeUnsupportedInputOnnxModel(directory);

    final InvalidFormatException exception = assertThrows(InvalidFormatException.class,
        () -> open(model));
    assertEquals("ONNX graph " + model + " declares unsupported inputs: [position_ids]",
        exception.getMessage());
  }

  @Test
  void testSupportsInt32InputIds(@TempDir Path directory) throws Exception {
    final Path model = EmbeddingTestFixtures.writeInt32InputOnnxModel(directory);

    try (TeacherEncoder encoder = open(model)) {
      assertArrayEquals(new float[] {3f}, encoder.encodeBatch(new long[][] {{2, 4}})[0]);
      final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
          () -> encoder.encodeBatch(new long[][] {{(long) Integer.MAX_VALUE + 1}}));
      assertEquals("input_ids[0][0] value 2147483648 does not fit an INT32 tensor",
          exception.getMessage());
    }
  }

  @Test
  void testRejectsRankOneInputIdsAtLoadTime(@TempDir Path directory) throws Exception {
    final Path model = EmbeddingTestFixtures.writeRankOneInputOnnxModel(directory);

    final InvalidFormatException exception = assertThrows(InvalidFormatException.class,
        () -> open(model));
    assertEquals("ONNX graph " + model + " input 'input_ids' must have rank 2, but has rank 1",
        exception.getMessage());
  }

  @Test
  void testSupportsInt32AttentionMask(@TempDir Path directory) throws Exception {
    final Path model = EmbeddingTestFixtures.writeInt32AttentionMaskOnnxModel(directory);

    try (TeacherEncoder encoder = open(model)) {
      assertArrayEquals(new float[] {3f}, encoder.encodeBatch(new long[][] {{2, 4}})[0]);
    }
  }

  @Test
  void testRejectsFloatInputIdsAtLoadTime(@TempDir Path directory) throws Exception {
    final Path model = EmbeddingTestFixtures.writeFloatInputOnnxModel(directory);

    final InvalidFormatException exception = assertThrows(InvalidFormatException.class,
        () -> open(model));
    assertEquals("ONNX graph " + model + " input 'input_ids' must be INT32 or INT64, but is "
        + "FLOAT", exception.getMessage());
  }

  @Test
  void testPrefersTheNamedLastHiddenStateOutput(@TempDir Path directory) throws Exception {
    final Path model = EmbeddingTestFixtures.writeMultipleOutputsOnnxModel(directory);

    try (TeacherEncoder encoder = open(model)) {
      assertArrayEquals(new float[] {3f}, encoder.encodeBatch(new long[][] {{2, 4}})[0]);
    }
  }

  @Test
  void testRejectsOutputDimensionsThatDoNotMatchTheInput(@TempDir Path directory)
      throws Exception {
    final Path model = EmbeddingTestFixtures.writeFixedOutputOnnxModel(directory);

    try (TeacherEncoder encoder = open(model)) {
      final EmbeddingException sequenceError = assertThrows(EmbeddingException.class,
          () -> encoder.encodeBatch(new long[][] {{2, 4}}));
      assertEquals("ONNX output 'last_hidden_state' row 0 has sequence dimension 1, expected 2",
          sequenceError.getMessage());

      final EmbeddingException batchError = assertThrows(EmbeddingException.class,
          () -> encoder.encodeBatch(new long[][] {{2}, {4}}));
      assertEquals("ONNX output 'last_hidden_state' batch dimension 1 does not match input "
          + "batch dimension 2", batchError.getMessage());
    }
  }

  @Test
  void testMeanPoolingDoesNotOverflowFiniteHiddenStates(@TempDir Path directory)
      throws Exception {
    final Path model = EmbeddingTestFixtures.writeMaxFloatOnnxModel(directory);

    try (TeacherEncoder encoder = open(model)) {
      assertArrayEquals(new float[] {Float.MAX_VALUE},
          encoder.encodeBatch(new long[][] {{1, 1}})[0]);
    }
  }

  /**
   * Opens a teacher through the provider the core lookup selects for it.
   *
   * @param model The ONNX file.
   * @return The open encoder.
   * @throws IOException Thrown if the graph cannot be loaded.
   */
  private TeacherEncoder open(Path model) throws IOException {
    final ProviderSpec spec = ProviderSpec.of(model);
    return Providers.of(TeacherEncoderProvider.class).select(spec).create(spec);
  }
}
