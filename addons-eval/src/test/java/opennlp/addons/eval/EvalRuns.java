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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared steps of the eval tests: the data locator of this JVM, the skip rules for absent data,
 * and the report step that writes the TSV, prints the summary and asserts every threshold.
 */
public final class EvalRuns {

  /** The system property naming the report directory; the pom points it at the build directory. */
  static final String OUT_PROPERTY = "opennlp.addons.eval.out";

  /** The data locator of this JVM. */
  public static final EvalData DATA = EvalData.fromEnvironment();

  /** The threshold of a metric that is recorded in the report but not gated. */
  public static final double RECORDED = 0.0;

  /** The prefix of every skip message, so a surefire report shows the reason at a glance. */
  public static final String SKIPPED = "skipped: ";

  /** The report directory when the pom does not set {@link #OUT_PROPERTY}. */
  private static final String DEFAULT_OUT = "target/eval-reports";

  private EvalRuns() {
  }

  /**
   * Skips the calling test unless the directory exists.
   *
   * @param directory The directory the evaluation needs.
   * @return The directory, for chaining.
   */
  public static Path assumeDirectory(Path directory) {
    assumeTrue(Files.isDirectory(directory),
        SKIPPED + "eval data directory not found: " + directory + " (set -D"
            + EvalData.DIRECTORY_PROPERTY + " or " + EvalData.DIRECTORY_VARIABLE + ")");
    return directory;
  }

  /**
   * Skips the calling test unless the file exists.
   *
   * @param file The file the evaluation needs.
   * @return The file, for chaining.
   */
  public static Path assumeFile(Path file) {
    assumeTrue(Files.isRegularFile(file), SKIPPED + "eval file not found: " + file);
    return file;
  }

  /**
   * Skips the calling test unless every named model file is in the model directory.
   *
   * @param fileNames The model file names.
   */
  public static void assumeModels(String... fileNames) {
    for (final String fileName : fileNames) {
      final Path model = DATA.model(fileName);
      assumeTrue(Files.isRegularFile(model), SKIPPED + "model not found: " + model
          + " (download it into " + DATA.models() + ")");
    }
  }

  /**
   * Writes the reports of one evaluation, prints one summary line per metric, and fails the
   * test if a threshold was missed.
   *
   * @param evaluation The evaluation name; the report file is {@code <evaluation>.tsv}.
   * @param reports The measured metrics.
   * @throws IOException Thrown if the report cannot be written.
   */
  public static void finish(String evaluation, List<EvalReport> reports) throws IOException {
    final Path out = Path.of(System.getProperty(OUT_PROPERTY, DEFAULT_OUT))
        .resolve(evaluation + ".tsv");
    EvalReport.write(out, reports);
    final List<String> failed = new ArrayList<>();
    for (final EvalReport report : reports) {
      System.out.println("[" + evaluation + "] " + report.summary());
      if (!report.pass()) {
        failed.add(report.summary());
      }
    }
    System.out.println("[" + evaluation + "] report written to " + out);
    assertTrue(failed.isEmpty(), evaluation + " missed " + failed.size() + " threshold(s): "
        + failed);
  }
}
