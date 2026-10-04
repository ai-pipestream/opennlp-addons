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

package opennlp.geo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import opennlp.tools.commons.ThreadSafe;
import opennlp.tools.geo.Gazetteer;
import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.geo.GeoPoint;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.StringUtil;

/**
 * A {@link Gazetteer} over a division table derived from Overture Maps data with the
 * {@code OvertureGenerator} developer tool in the project source tree: one tab-separated row
 * per division with id, primary name, comma-separated alternate names, coordinates,
 * country code, Overture subtype, and population.
 *
 * <p>The upstream <a href="https://docs.overturemaps.org/guides/divisions/">divisions
 * theme</a> is published under the
 * <a href="https://opendatacommons.org/licenses/odbl/1-0/">Open Database License</a>, whose
 * attribution and database share-alike terms follow the derived table. The upstream data uses
 * partitioned Parquet; the derivation script flattens the division features into this plain table.
 * Its output header records the source. Because divisions include countries and regions, not only
 * settlements, a derived table also resolves mentions like {@code Australia} or {@code Bavaria}
 * that place-only gazetteers miss.</p>
 *
 * <p>Lookup matches the primary and every alternate name by the one matching rule of this
 * module's gazetteers: names and queries are folded through NFC, case fold, accent fold and
 * UAX&#160;#29 word tokens joined by one space. Candidates are ranked by population
 * descending. Subtypes map coarsely:
 * {@code locality} rows become {@link GazetteerEntry#FEATURE_CLASS_CITY}; the
 * sub-locality subtypes ({@code borough}, {@code macrohood}, {@code neighborhood},
 * {@code microhood}) become {@link GazetteerEntry#FEATURE_CLASS_POI}; every other
 * subtype, countries through local administrative areas, becomes
 * {@link GazetteerEntry#FEATURE_CLASS_ADMIN}.</p>
 *
 * <p>Instances are immutable after loading and safe to share between threads.</p>
 */
@ThreadSafe
public final class OvertureGazetteer implements Gazetteer {

  /** The dataset identifier this gazetteer scopes its record ids to. */
  public static final String SOURCE = "overture";

  private static final int COLUMNS = 8;

  /** The ASCII tab the derived division format defines between the fields of one row. */
  private static final char FIELD_SEPARATOR = '\t';

  /** The separator between the elements of the alternate-names field. */
  private static final char LIST_SEPARATOR = ',';

  private static final Set<String> SUB_LOCALITY_SUBTYPES =
      Set.of("borough", "macrohood", "neighborhood", "microhood");

  private final GazetteerIndex index;

  private OvertureGazetteer(GazetteerIndex index) {
    this.index = index;
  }

  /**
   * Loads a derived division table from a file.
   *
   * @param table The tab-separated table. Must not be {@code null}.
   * @return A loaded {@link OvertureGazetteer}. Never {@code null}.
   * @throws IOException Thrown if reading fails.
   * @throws InvalidFormatException Thrown if the table contains no rows or a row is not
   *         in the expected format.
   * @throws IllegalArgumentException Thrown if {@code table} is {@code null}.
   */
  public static OvertureGazetteer load(Path table) throws IOException {
    if (table == null) {
      throw new IllegalArgumentException("table must not be null");
    }
    try (InputStream in = Files.newInputStream(table)) {
      return load(in);
    }
  }

  /**
   * Loads a derived division table from a stream.
   *
   * @param in The tab-separated content. Must not be {@code null}. The stream is read
   *           fully but not closed, and a leading byte order mark is ignored. Lines
   *           starting with {@code #} carry the derivation record and are skipped.
   * @return A loaded {@link OvertureGazetteer}. Never {@code null}.
   * @throws IOException Thrown if reading fails.
   * @throws InvalidFormatException Thrown if the content has no rows or a row is not in
   *         the expected format.
   * @throws IllegalArgumentException Thrown if {@code in} is {@code null}.
   */
  public static OvertureGazetteer load(InputStream in) throws IOException {
    if (in == null) {
      throw new IllegalArgumentException("in must not be null");
    }
    return new OvertureGazetteer(
        GazetteerIndex.load(in, true, OvertureGazetteer::parseRow));
  }

  /** {@inheritDoc} */
  @Override
  public List<GazetteerEntry> lookup(CharSequence name) {
    if (name == null) {
      throw new IllegalArgumentException("name must not be null");
    }
    return index.lookup(name);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<GazetteerEntry> byId(String source, String recordId) {
    if (source == null) {
      throw new IllegalArgumentException("source must not be null");
    }
    if (recordId == null) {
      throw new IllegalArgumentException("recordId must not be null");
    }
    return index.byId(source, recordId);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<GazetteerEntry> byRegion(String isoCountryCode) {
    return index.byRegion(isoCountryCode);
  }

  /** {@inheritDoc} */
  @Override
  public Set<String> sources() {
    return Set.of(SOURCE);
  }

  /** Parses one derived row into an entry and includes the line number in format errors. */
  private static GazetteerEntry parseRow(String line, int lineNumber)
      throws InvalidFormatException {
    final String[] fields = GazetteerIndex.split(line, FIELD_SEPARATOR);
    if (fields.length != COLUMNS) {
      throw new InvalidFormatException("line " + lineNumber + " has " + fields.length
          + " columns, expected " + COLUMNS);
    }
    try {
      final String id = trim(fields[0]);
      final String name = trim(fields[1]);
      final Set<String> alternates = new LinkedHashSet<>();
      for (final String alternate : GazetteerIndex.split(fields[2], LIST_SEPARATOR)) {
        final String trimmed = trim(alternate);
        if (!trimmed.isEmpty() && !trimmed.equals(name)) {
          alternates.add(trimmed);
        }
      }
      final GeoPoint location = new GeoPoint(
          Double.parseDouble(trim(fields[3])), Double.parseDouble(trim(fields[4])));
      final String country = trim(fields[5]);
      final String countryCode = country.isEmpty() ? null : country;
      final String population = trim(fields[7]);
      return new GazetteerEntry(SOURCE, id, name, List.copyOf(alternates), location,
          countryCode, List.of(), population.isEmpty() ? 0L : Long.parseLong(population),
          featureClass(trim(fields[6])), Map.of());
    } catch (IllegalArgumentException e) {
      throw new InvalidFormatException(
          "line " + lineNumber + " is not a derived division row: " + e.getMessage(), e);
    }
  }

  /** Maps the Overture division subtype onto the coarse conventional classes. */
  private static String featureClass(String subtype) {
    if ("locality".equals(subtype)) {
      return GazetteerEntry.FEATURE_CLASS_CITY;
    }
    return SUB_LOCALITY_SUBTYPES.contains(subtype)
        ? GazetteerEntry.FEATURE_CLASS_POI : GazetteerEntry.FEATURE_CLASS_ADMIN;
  }

  /**
   * Trims a cell by Unicode whitespace, so padding with a no-break or ideographic space is
   * removed like ASCII padding.
   *
   * @param cell The cell content. Must not be {@code null}.
   * @return The cell without leading and trailing Unicode whitespace.
   */
  private static String trim(String cell) {
    return StringUtil.trimUnicodeWhitespace(cell);
  }
}
