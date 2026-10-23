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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import opennlp.tools.util.StringUtil;

/**
 * One measured metric of an add-on evaluation and its verdict against a documented threshold.
 *
 * <p>Reports are written as tab-separated rows with {@link #TSV_HEADER}, one file per
 * evaluation, so a run's figures can be compared across commits. Field values are plain text:
 * not blank and without a tab or line break, so each row stays one line.</p>
 *
 * @param metric The metric name, such as {@code upos.accuracy}.
 * @param dataset The dataset the metric was measured on.
 * @param model The model, or model configuration, that was evaluated.
 * @param value The measured value.
 * @param threshold The value the measurement is held against.
 * @param pass Whether the measurement meets the threshold.
 *
 * @since 3.0.0
 */
public record EvalReport(String metric, String dataset, String model, double value,
    double threshold, boolean pass) {

  /** The header line of a report file. */
  public static final String TSV_HEADER = "metric\tdataset\tmodel\tvalue\tthreshold\tpass";

  private static final String NUMBER_FORMAT = "%.4f";

  /**
   * Validates one report.
   *
   * @throws IllegalArgumentException Thrown if a text field is {@code null}, blank, or holds a
   *                                  tab or line break, or if a number is not finite.
   */
  public EvalReport {
    requireField(metric, "metric");
    requireField(dataset, "dataset");
    requireField(model, "model");
    requireFinite(value, "value");
    requireFinite(threshold, "threshold");
  }

  /**
   * Reports a metric that passes when it reaches the threshold.
   *
   * @param metric The metric name. Must be a plain text field.
   * @param dataset The dataset name. Must be a plain text field.
   * @param model The model name. Must be a plain text field.
   * @param value The measured value. Must be finite.
   * @param threshold The lowest passing value. Must be finite.
   * @return The report; it passes when {@code value >= threshold}. Never {@code null}.
   * @throws IllegalArgumentException Thrown if a field is invalid.
   */
  public static EvalReport atLeast(String metric, String dataset, String model, double value,
      double threshold) {
    return new EvalReport(metric, dataset, model, value, threshold, value >= threshold);
  }

  /** {@return this report as one tab-separated row matching {@link #TSV_HEADER}} */
  public String toTsv() {
    return metric + '\t' + dataset + '\t' + model + '\t' + number(value) + '\t'
        + number(threshold) + '\t' + pass;
  }

  /** {@return a one-line summary: the verdict, the metric, the dataset, the model, the values} */
  public String summary() {
    return (pass ? "PASS " : "FAIL ") + metric + " on " + dataset + " with " + model + ": "
        + number(value) + " (threshold " + number(threshold) + ")";
  }

  /**
   * Writes reports as a TSV file, replacing an earlier file and creating the parent
   * directories.
   *
   * @param file The file to write. Must not be {@code null}.
   * @param reports The rows in order. Must not be {@code null}, empty, or contain {@code null}.
   * @throws IOException Thrown if the file cannot be written.
   * @throws IllegalArgumentException Thrown if an argument violates the constraints above.
   */
  public static void write(Path file, List<EvalReport> reports) throws IOException {
    if (file == null) {
      throw new IllegalArgumentException("file must not be null");
    }
    if (reports == null || reports.isEmpty()) {
      throw new IllegalArgumentException("reports must not be null or empty");
    }
    for (final EvalReport report : reports) {
      if (report == null) {
        throw new IllegalArgumentException("reports must not contain null");
      }
    }
    final Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      writer.write(TSV_HEADER);
      writer.newLine();
      for (final EvalReport report : reports) {
        writer.write(report.toTsv());
        writer.newLine();
      }
    }
  }

  /**
   * Formats a number for a row, independent of the default locale.
   *
   * @param number The number.
   * @return The number with four decimals.
   */
  private static String number(double number) {
    return String.format(Locale.ROOT, NUMBER_FORMAT, number);
  }

  /**
   * Checks a text field.
   *
   * @param field The field value.
   * @param name The field name for the error message.
   * @throws IllegalArgumentException Thrown if the field is not plain text.
   */
  private static void requireField(String field, String name) {
    if (field == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    if (StringUtil.isUnicodeBlank(field)) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (field.indexOf('\t') >= 0 || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(name + " must not contain a tab or line break: "
          + field);
    }
  }

  /**
   * Checks a number.
   *
   * @param number The number.
   * @param name The field name for the error message.
   * @throws IllegalArgumentException Thrown if the number is NaN or infinite.
   */
  private static void requireFinite(double number, String name) {
    if (!Double.isFinite(number)) {
      throw new IllegalArgumentException(name + " must be finite: " + number);
    }
  }
}
