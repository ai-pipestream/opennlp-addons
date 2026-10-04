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
package opennlp.addons.eval.subword;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import opennlp.addons.eval.EvalReport;
import opennlp.addons.eval.EvalRuns;
import opennlp.subword.sentencepiece.SentencePieceTokenizer;
import opennlp.tools.tokenize.SubwordPiece;
import opennlp.tools.util.StringUtil;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks {@link SentencePieceTokenizer} against the reference SentencePiece implementation on
 * real pre-trained models, piece by piece.
 *
 * <p>Data: {@code <eval root>/sentencepiece/} holds {@code <name>.model} files with sibling
 * {@code <name>.fixtures.tsv} files written by the {@code gen_real_fixtures.py} script of the
 * subword test resources: one line per input with the input, the piece count, four columns
 * per piece (content, id, start, end) and the normalized form, escaped as that script writes
 * them. A model without fixtures is skipped with a message.</p>
 *
 * <p>The threshold is exact parity: every fixture of every model must match in pieces, ids,
 * spans and normalized form. The fixture reader here mirrors the one in the subword test
 * tree, which is not on the compile path of this module; moving it into subword's main code
 * is listed as a follow-up in the README.</p>
 */
class SentencePieceParityEvalTest {

  static final String DATASET = "sentencepiece";
  static final String MODEL_SUFFIX = ".model";
  static final String FIXTURES_SUFFIX = ".fixtures.tsv";

  /** One fixture line: the input, the expected pieces and the expected normalized form. */
  record Fixture(String input, List<SubwordPiece> pieces, String normalized) {
  }

  @Test
  void testRealModelParity() throws IOException {
    final Path dataset = EvalRuns.assumeDirectory(EvalRuns.DATA.dataset(DATASET));
    final List<Path> models = new ArrayList<>();
    try (Stream<Path> files = Files.list(dataset)) {
      files.filter(f -> f.getFileName().toString().endsWith(MODEL_SUFFIX)).sorted()
          .forEach(models::add);
    }
    assumeTrue(!models.isEmpty(), "skipped: no " + MODEL_SUFFIX + " files in " + dataset);

    final List<EvalReport> reports = new ArrayList<>();
    for (final Path model : models) {
      final String name = model.getFileName().toString();
      final Path fixturesFile = dataset.resolve(
          name.substring(0, name.length() - MODEL_SUFFIX.length()) + FIXTURES_SUFFIX);
      assumeTrue(Files.isRegularFile(fixturesFile), "skipped: no fixtures for " + model
          + ", expected " + fixturesFile);
      final SentencePieceTokenizer tokenizer = SentencePieceTokenizer.load(model);
      final List<Fixture> fixtures = readFixtures(fixturesFile);
      int pieceMatches = 0;
      int normalizedMatches = 0;
      for (final Fixture fixture : fixtures) {
        if (fixture.pieces().equals(tokenizer.encode(fixture.input()))) {
          pieceMatches++;
        }
        if (fixture.normalized().contentEquals(tokenizer.normalize(fixture.input()))) {
          normalizedMatches++;
        }
      }
      reports.add(EvalReport.atLeast("fixtures", DATASET, name, fixtures.size(), 30));
      reports.add(EvalReport.atLeast("piece.parity", DATASET, name,
          share(pieceMatches, fixtures.size()), 1.0));
      reports.add(EvalReport.atLeast("normalized.parity", DATASET, name,
          share(normalizedMatches, fixtures.size()), 1.0));
    }
    EvalRuns.finish(DATASET, reports);
  }

  private static double share(int matches, int total) {
    return total == 0 ? 0.0 : (double) matches / total;
  }

  /**
   * Reads a fixture file.
   *
   * @param file The fixture file.
   * @return The fixtures in file order.
   * @throws IOException Thrown if the file cannot be read.
   */
  static List<Fixture> readFixtures(Path file) throws IOException {
    final List<Fixture> fixtures = new ArrayList<>();
    for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (line.isEmpty()) {
        continue;
      }
      final String[] cols = StringUtil.split(line, '\t');
      final String input = unescape(cols[0]);
      final int count = Integer.parseInt(cols[1]);
      final List<SubwordPiece> pieces = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        pieces.add(new SubwordPiece(unescape(cols[2 + i * 4]),
            Integer.parseInt(cols[3 + i * 4]), Integer.parseInt(cols[4 + i * 4]),
            Integer.parseInt(cols[5 + i * 4])));
      }
      fixtures.add(new Fixture(input, pieces, unescape(cols[2 + count * 4])));
    }
    return fixtures;
  }

  /**
   * Reverses the fixture escaping: {@code \t \n \r} are the controls, {@code \s} a space,
   * {@code \d} U+2014, {@code \e} the empty value and {@code \\} a backslash.
   *
   * @param s The escaped cell.
   * @return The cell text.
   * @throws IllegalArgumentException Thrown if the cell holds an unknown escape.
   */
  static String unescape(String s) {
    final StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        i++;
        switch (s.charAt(i)) {
          case 't' -> out.append('\t');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 's' -> out.append(' ');
          case 'd' -> out.appendCodePoint(0x2014);
          case 'e' -> out.append("");
          case '\\' -> out.append('\\');
          default -> throw new IllegalArgumentException("bad escape in fixture: " + s);
        }
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
