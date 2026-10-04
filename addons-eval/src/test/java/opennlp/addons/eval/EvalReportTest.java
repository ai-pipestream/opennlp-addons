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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalReportTest {

  @Test
  void testAtLeastPassesOnTheThreshold() {
    final EvalReport report = EvalReport.atLeast("recallAt10", "vector-search", "model", 0.8, 0.8);
    assertTrue(report.pass());
    assertEquals("recallAt10", report.metric());
    assertEquals(0.8, report.value());
  }

  @Test
  void testAtLeastFailsBelowTheThreshold() {
    assertFalse(EvalReport.atLeast("recallAt10", "vector-search", "model", 0.79, 0.8).pass());
  }

  @Test
  void testTsvLineAndHeaderAgree() {
    final EvalReport report = EvalReport.atLeast("upos.accuracy", "ud", "bilstm", 0.95125, 0.85);
    assertEquals("metric\tdataset\tmodel\tvalue\tthreshold\tpass", EvalReport.TSV_HEADER);
    assertEquals("upos.accuracy\tud\tbilstm\t0.9513\t0.8500\ttrue", report.toTsv());
  }

  @Test
  void testSummaryNamesTheOutcome() {
    assertEquals("PASS upos.accuracy on ud with bilstm: 0.9513 (threshold 0.8500)",
        EvalReport.atLeast("upos.accuracy", "ud", "bilstm", 0.95125, 0.85).summary());
    assertEquals("FAIL conll on ontogum with rules: 0.0400 (threshold 0.0500)",
        EvalReport.atLeast("conll", "ontogum", "rules", 0.04, 0.05).summary());
  }

  @Test
  void testRejectsNullFields() {
    assertThrows(IllegalArgumentException.class,
        () -> EvalReport.atLeast(null, "ud", "bilstm", 1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> EvalReport.atLeast("metric", null, "bilstm", 1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> EvalReport.atLeast("metric", "ud", null, 1, 1));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", " ", "　", " ", "a\tb", "a\nb", "a\rb"})
  void testRejectsBlankAndUnsafeFields(String field) {
    assertThrows(IllegalArgumentException.class,
        () -> EvalReport.atLeast(field, "ud", "bilstm", 1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> EvalReport.atLeast("metric", field, "bilstm", 1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> EvalReport.atLeast("metric", "ud", field, 1, 1));
  }

  @Test
  void testKeepsInnerSpacesInFields() {
    assertEquals("en ner person", EvalReport.atLeast("m", "d", "en ner person", 1, 1).model());
  }

  @Test
  void testRejectsNonFiniteNumbers() {
    assertThrows(IllegalArgumentException.class,
        () -> EvalReport.atLeast("m", "d", "x", Double.NaN, 1));
    assertThrows(IllegalArgumentException.class,
        () -> EvalReport.atLeast("m", "d", "x", 1, Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class,
        () -> new EvalReport("m", "d", "x", Double.NEGATIVE_INFINITY, 1, false));
  }

  @Test
  void testWriteProducesHeaderAndOneLinePerReport(@TempDir Path dir) throws IOException {
    final Path file = dir.resolve("reports").resolve("vector-search.tsv");
    final List<EvalReport> reports = List.of(
        EvalReport.atLeast("a", "d", "m", 1, 0.5),
        EvalReport.atLeast("b", "d", "m", 0.25, 0.5));
    EvalReport.write(file, reports);
    final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertEquals(Arrays.asList(EvalReport.TSV_HEADER, reports.get(0).toTsv(),
        reports.get(1).toTsv()), lines);
  }

  @Test
  void testWriteReplacesAnEarlierFile(@TempDir Path dir) throws IOException {
    final Path file = dir.resolve("r.tsv");
    EvalReport.write(file, List.of(EvalReport.atLeast("a", "d", "m", 1, 0.5)));
    EvalReport.write(file, List.of(EvalReport.atLeast("b", "d", "m", 1, 0.5)));
    assertEquals(2, Files.readAllLines(file, StandardCharsets.UTF_8).size());
  }

  @Test
  void testWriteRejectsBadArguments(@TempDir Path dir) {
    final Path file = dir.resolve("r.tsv");
    final List<EvalReport> one = List.of(EvalReport.atLeast("a", "d", "m", 1, 0.5));
    assertThrows(IllegalArgumentException.class, () -> EvalReport.write(null, one));
    assertThrows(IllegalArgumentException.class, () -> EvalReport.write(file, null));
    assertThrows(IllegalArgumentException.class, () -> EvalReport.write(file, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> EvalReport.write(file, Arrays.asList(one.get(0), null)));
  }
}
