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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import opennlp.tools.commons.ThreadSafe;
import opennlp.tools.geo.AttributeValue;
import opennlp.tools.geo.Gazetteer;
import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.geo.GeoBoundingBox;
import opennlp.tools.geo.GeoPoint;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.StringUtil;

/**
 * A {@link Gazetteer} over a user-authored tab-separated file, so places a public dataset does
 * not know, for example customer sites or internal facility names, take part in geocoding and
 * everything downstream of it. Typically composed with a base gazetteer through an
 * {@link OverlayGazetteer}.
 *
 * <p>One row per place, columns tab-separated in this order, where trailing columns may be
 * omitted and an empty column means absent:</p>
 *
 * <ol>
 *   <li>record id, unique within the file</li>
 *   <li>canonical name</li>
 *   <li>alternate names, separated by {@code |}</li>
 *   <li>latitude in decimal degrees</li>
 *   <li>longitude in decimal degrees</li>
 *   <li><a href="https://www.iso.org/iso-3166-country-codes.html">ISO 3166-1</a> alpha-2
 *       country code</li>
 *   <li>feature class, conventionally one of the {@code FEATURE_CLASS_*} constants on
 *       {@link GazetteerEntry}</li>
 *   <li>population</li>
 *   <li>bounding box as {@code west,south,east,north} in decimal degrees, the axis order of
 *       <a href="https://datatracker.ietf.org/doc/html/rfc7946#section-5">RFC 7946,
 *       section 5</a></li>
 *   <li>administrative containment chain, outermost first, separated by {@code |}</li>
 *   <li>further columns: one {@code key=value} attribute each</li>
 * </ol>
 *
 * <p>A row needs either both coordinates or a bounding box; with only a bounding box the point
 * location becomes the box's {@link GeoBoundingBox#center() center}. Free-text metadata such as
 * a postal address belongs in an attribute column; it is stored as provided and is not parsed or
 * matched. Lines whose first character is {@code #} and blank lines are skipped. The file is
 * read as UTF-8, ignoring a leading byte order mark. Names containing a tab or {@code |} are not
 * representable in this format.</p>
 *
 * <p>Lookup matches the canonical and alternate names by the one matching rule of this module's
 * gazetteers: names and queries are folded through NFC, case fold, accent fold and
 * UAX&#160;#29 word tokens joined by one space. Candidates are ranked like the other loaders of
 * this module, by population descending with the feature-class prior on ties. Instances are
 * immutable after loading and safe to share between threads.</p>
 */
@ThreadSafe
public final class UserGazetteer implements Gazetteer {

  /** The ASCII tab this file format defines between the fields of one row. */
  private static final char FIELD_SEPARATOR = '\t';

  /** The separator between the elements of the alternate-names and containment fields. */
  private static final char LIST_SEPARATOR = '|';

  /** The separator between the edges of the bounding-box field. */
  private static final char BOX_SEPARATOR = ',';

  /** The separator between an attribute key and its value. */
  private static final char ATTRIBUTE_SEPARATOR = '=';

  /** The number of edges in the bounding-box field. */
  private static final int BOX_EDGES = 4;

  /** The failure for a missing table path. */
  private static final String TABLE_REQUIRED = "table must not be null";

  /** The failure for a missing input stream. */
  private static final String IN_REQUIRED = "in must not be null";

  private final String source;
  private final GazetteerIndex index;

  private UserGazetteer(String source, GazetteerIndex index) {
    this.source = source;
    this.index = index;
  }

  /**
   * Loads a user gazetteer from a file.
   *
   * @param table  The tab-separated table. Must not be {@code null}.
   * @param source The dataset identifier the loaded records are scoped to, for example
   *               {@code customer}. Must not be {@code null} or blank.
   * @return A loaded {@link UserGazetteer}. Never {@code null}.
   * @throws IOException Thrown if reading fails.
   * @throws InvalidFormatException Thrown if the table contains no rows, a row is not in
   *         the documented format, or a record id repeats.
   * @throws IllegalArgumentException Thrown if an argument violates its constraint.
   */
  public static UserGazetteer load(Path table, String source) throws IOException {
    if (table == null) {
      throw new IllegalArgumentException(TABLE_REQUIRED);
    }
    validateSource(source);
    try (InputStream in = Files.newInputStream(table)) {
      return load(in, source);
    }
  }

  /**
   * Loads a user gazetteer from a stream.
   *
   * @param in     The tab-separated content. Must not be {@code null}. The stream is read fully
   *               but not closed, and a leading byte order mark is ignored.
   * @param source The dataset identifier the loaded records are scoped to, for example
   *               {@code customer}. Must not be {@code null} or blank.
   * @return A loaded {@link UserGazetteer}. Never {@code null}.
   * @throws IOException Thrown if reading fails.
   * @throws InvalidFormatException Thrown if the content contains no rows, a row is not
   *         in the documented format, or a record id repeats.
   * @throws IllegalArgumentException Thrown if an argument violates its constraint.
   */
  public static UserGazetteer load(InputStream in, String source) throws IOException {
    if (in == null) {
      throw new IllegalArgumentException(IN_REQUIRED);
    }
    validateSource(source);
    final GazetteerIndex index = GazetteerIndex.load(in, true,
        (line, lineNumber) -> parseRow(line, lineNumber, source));
    return new UserGazetteer(source, index);
  }

  /**
   * Loads {@link Suppression} rules from a file: one rule per line with up to three
   * tab-separated columns, name, country code, and feature class, where trailing columns may be
   * omitted and an empty column means the filter is absent. Comment and blank lines are skipped
   * like in the entry format, and the file is read as UTF-8, ignoring a leading byte order mark.
   * An empty file holds no rules.
   *
   * @param table The tab-separated rules. Must not be {@code null}.
   * @return The rules in file order. Never {@code null}; possibly empty.
   * @throws IOException Thrown if reading fails.
   * @throws InvalidFormatException Thrown if a line is not a valid rule.
   * @throws IllegalArgumentException Thrown if {@code table} is {@code null}.
   */
  public static List<Suppression> loadSuppressions(Path table) throws IOException {
    if (table == null) {
      throw new IllegalArgumentException(TABLE_REQUIRED);
    }
    try (InputStream in = Files.newInputStream(table)) {
      return loadSuppressions(in);
    }
  }

  /**
   * Loads {@link Suppression} rules from a stream in the format documented at
   * {@link #loadSuppressions(Path)}.
   *
   * @param in The tab-separated rules. Must not be {@code null}. The stream is read fully but
   *           not closed.
   * @return The rules in file order. Never {@code null}; possibly empty.
   * @throws IOException Thrown if reading fails.
   * @throws InvalidFormatException Thrown if a line is not a valid rule.
   * @throws IllegalArgumentException Thrown if {@code in} is {@code null}.
   */
  public static List<Suppression> loadSuppressions(InputStream in) throws IOException {
    if (in == null) {
      throw new IllegalArgumentException(IN_REQUIRED);
    }
    final List<Suppression> rules = new ArrayList<>();
    final BufferedReader reader = GazetteerIndex.utf8Reader(in);
    String line;
    int lineNumber = 0;
    while ((line = reader.readLine()) != null) {
      lineNumber++;
      if (lineNumber == 1) {
        line = StringUtil.stripByteOrderMark(line);
      }
      if (StringUtil.isUnicodeBlank(line) || line.charAt(0) == '#') {
        continue;
      }
      final String[] fields = GazetteerIndex.split(line, FIELD_SEPARATOR);
      if (fields.length > 3) {
        throw new InvalidFormatException("line " + lineNumber + " has " + fields.length
            + " columns, expected at most 3");
      }
      try {
        rules.add(new Suppression(trim(fields[0]),
            absent(fields, 1) ? null : trim(fields[1]),
            absent(fields, 2) ? null : trim(fields[2])));
      } catch (IllegalArgumentException e) {
        throw new InvalidFormatException(
            "line " + lineNumber + " is not a suppression rule: " + e.getMessage(), e);
      }
    }
    return List.copyOf(rules);
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
    return Set.of(source);
  }

  /** Parses one row into an entry and includes the line number in format errors. */
  private static GazetteerEntry parseRow(String line, int lineNumber, String source)
      throws InvalidFormatException {
    final String[] fields = GazetteerIndex.split(line, FIELD_SEPARATOR);
    if (fields.length < 2) {
      throw new InvalidFormatException("line " + lineNumber + " has " + fields.length
          + " columns, expected at least record id and name");
    }
    try {
      final GeoBoundingBox box = absent(fields, 8) ? null : parseBox(trim(fields[8]));
      final Map<String, AttributeValue> attributes = new LinkedHashMap<>();
      for (int i = 10; i < fields.length; i++) {
        final String field = trim(fields[i]);
        final int separator = field.indexOf(ATTRIBUTE_SEPARATOR);
        if (separator < 1) {
          throw new IllegalArgumentException(
              "an attribute column must read key=value, got: " + field);
        }
        final String key = trim(field.substring(0, separator));
        if (attributes.put(key, new AttributeValue(
            trim(field.substring(separator + 1)), source, "")) != null) {
          throw new IllegalArgumentException("the attribute key repeats: " + key);
        }
      }
      return new GazetteerEntry(source, trim(fields[0]), trim(fields[1]),
          absent(fields, 2) ? List.of() : names(fields[2]),
          location(fields, box), box,
          absent(fields, 5) ? null : trim(fields[5]),
          absent(fields, 9) ? List.of() : names(fields[9]),
          absent(fields, 7) ? 0L : Long.parseLong(trim(fields[7])),
          absent(fields, 6) ? null : trim(fields[6]),
          attributes);
    } catch (IllegalArgumentException e) {
      throw new InvalidFormatException(
          "line " + lineNumber + " is not a gazetteer row: " + e.getMessage(), e);
    }
  }

  /**
   * Resolves a row's point location: both coordinate columns when present, otherwise the center
   * of the bounding box.
   *
   * @throws IllegalArgumentException Thrown if only one coordinate is present, or both are
   *     absent and there is no bounding box.
   */
  private static GeoPoint location(String[] fields, GeoBoundingBox box) {
    final boolean hasLatitude = !absent(fields, 3);
    final boolean hasLongitude = !absent(fields, 4);
    if (hasLatitude != hasLongitude) {
      throw new IllegalArgumentException(
          "latitude and longitude must be present together or absent together");
    }
    if (hasLatitude) {
      return new GeoPoint(
          Double.parseDouble(trim(fields[3])), Double.parseDouble(trim(fields[4])));
    }
    if (box == null) {
      throw new IllegalArgumentException("a row needs coordinates or a bounding box");
    }
    return box.center();
  }

  /**
   * Parses the bounding-box field: four decimal edges in the order west, south, east, north.
   *
   * @throws IllegalArgumentException Thrown if the field does not hold four valid edges.
   */
  private static GeoBoundingBox parseBox(String field) {
    final String[] edges = GazetteerIndex.split(field, BOX_SEPARATOR);
    if (edges.length != BOX_EDGES) {
      throw new IllegalArgumentException(
          "a bounding box must read west,south,east,north, got: " + field);
    }
    return new GeoBoundingBox(Double.parseDouble(trim(edges[0])),
        Double.parseDouble(trim(edges[1])), Double.parseDouble(trim(edges[2])),
        Double.parseDouble(trim(edges[3])));
  }

  /** Splits a {@code |}-separated list field, dropping blank elements. */
  private static List<String> names(String field) {
    final List<String> elements = new ArrayList<>();
    int start = 0;
    for (int i = 0; i <= field.length(); i++) {
      if (i == field.length() || field.charAt(i) == LIST_SEPARATOR) {
        final String trimmed = trim(field.substring(start, i));
        if (!trimmed.isEmpty()) {
          elements.add(trimmed);
        }
        start = i + 1;
      }
    }
    return elements;
  }

  /** {@return {@code true} if the column at {@code i} is missing or empty after trimming} */
  private static boolean absent(String[] fields, int i) {
    return fields.length <= i || trim(fields[i]).isEmpty();
  }

  /**
   * Validates the source identifier shared by both load entry points.
   *
   * @param source The source identifier. Must not be {@code null} or blank.
   * @throws IllegalArgumentException Thrown if {@code source} is {@code null} or blank.
   */
  private static void validateSource(String source) {
    if (StringUtil.isUnicodeBlank(source)) {
      throw new IllegalArgumentException("source must not be null or blank");
    }
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
