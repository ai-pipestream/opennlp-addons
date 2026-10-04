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
import java.io.UncheckedIOException;
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
import opennlp.tools.geo.GeoPoint;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.StringUtil;

/**
 * The bundled {@link Gazetteer}: a public-domain populated-places table derived from Natural
 * Earth, shipped in this jar so location lookup works with no download or configuration. The
 * table is parsed once, lazily, on first {@link #getInstance()}. Format errors identify the
 * resource and line of a malformed row. After loading an instance is immutable and
 * thread-safe.
 *
 * <p>Lookup uses the one matching rule of this module's gazetteers: names and queries are
 * folded through NFC, case fold, accent fold and
 * <a href="https://unicode.org/reports/tr29/">UAX&#160;#29</a> word tokens joined by one
 * space, so queries match across case, accents, whitespace and hyphenation. The bundled table
 * is pure ASCII; native-script names are not matchable against it.</p>
 *
 * <p>{@link #lookup(CharSequence)} returns candidates ordered by population descending, then a
 * feature-class prior ({@link GazetteerEntry#FEATURE_CLASS_CITY} before
 * {@link GazetteerEntry#FEATURE_CLASS_ADMIN} before {@link GazetteerEntry#FEATURE_CLASS_POI}),
 * then source and record id for a deterministic total order. {@link #byRegion(String)} returns
 * the most populous bundled entry for the region. An {@link InMemoryGazetteer} indexes
 * caller-supplied entries the same way, without touching the bundled table or the shared
 * instance.</p>
 */
@ThreadSafe
public final class BundledGazetteer implements Gazetteer {

  private static final String RESOURCE = "naturalearth-populated-places.txt";

  /** The number of semicolon separated fields in one row of the bundled table format. */
  private static final int FIELD_COUNT = 11;

  /** The number of semicolons separating {@link #FIELD_COUNT} fields. */
  private static final int SEPARATOR_COUNT = FIELD_COUNT - 1;

  private final GazetteerIndex index;

  /**
   * Indexes the given entries. Package-private; callers go through {@link #getInstance()} for
   * the bundled table or {@link InMemoryGazetteer#fromEntries(List)} for their own entries.
   *
   * @throws IllegalArgumentException Thrown if {@code entries} is {@code null}, contains a
   *     {@code null} element, contains two entries with the same (source, recordId), or
   *     contains an entry with a name that folds to an empty match key.
   */
  BundledGazetteer(List<GazetteerEntry> entries) {
    this.index = GazetteerIndex.of(entries);
  }

  /**
   * {@return the shared instance backed by the bundled table} The table is loaded and indexed
   * once, on first access; later calls return the same immutable instance. A missing or
   * malformed bundled resource fails the one-time class initialization, so the error that
   * names the resource (and, for malformed data, the line) surfaces wrapped in a
   * {@link ExceptionInInitializerError} on the first call.
   */
  public static BundledGazetteer getInstance() {
    return Holder.INSTANCE;
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
    return index.sources();
  }

  /**
   * Parses gazetteer rows from a stream of the bundled table format.
   *
   * <p>Row format, eleven semicolon separated fields (documented in the data file header):
   * {@code source;recordId;name;altNames;lat;lon;iso2;containment;population;featureClass;
   * attributes} where altNames and containment are pipe separated lists (possibly empty), iso2
   * and featureClass may be empty for unknown, and attributes is a pipe separated list of
   * {@code key=value} pairs (possibly empty) whose provenance is the row's source. A line
   * starting with {@code #} is a comment; blank lines are skipped; data rows carry no inline
   * comments.</p>
   *
   * @param in           The table content, read fully as UTF-8 and closed.
   * @param resourceName The name to report in error messages.
   * @return The parsed entries in file order, never {@code null}.
   * @throws IOException Thrown if reading fails.
   * @throws InvalidFormatException Thrown for any malformed row; the message names
   *     {@code resourceName} and the line number.
   */
  static List<GazetteerEntry> parse(InputStream in, String resourceName) throws IOException {
    final List<GazetteerEntry> entries = new ArrayList<>();
    try (BufferedReader reader = GazetteerIndex.utf8Reader(in)) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (StringUtil.isUnicodeBlank(line) || line.charAt(0) == '#') {
          continue;
        }
        entries.add(parseRow(line, resourceName, lineNumber));
      }
    }
    return entries;
  }

  /**
   * Parses one data line into a gazetteer entry.
   *
   * @throws InvalidFormatException Thrown if the line is malformed; the message names
   *     {@code resourceName} and {@code lineNumber}.
   */
  private static GazetteerEntry parseRow(String line, String resourceName, int lineNumber)
      throws InvalidFormatException {
    // Scan the line into exactly FIELD_COUNT semicolon-separated fields.
    final String[] fields = new String[FIELD_COUNT];
    int fieldCount = 0;
    int start = 0;
    while (fieldCount < SEPARATOR_COUNT) {
      final int semicolon = line.indexOf(';', start);
      if (semicolon < 0) {
        throw malformed(resourceName, lineNumber, line, null);
      }
      fields[fieldCount++] = line.substring(start, semicolon);
      start = semicolon + 1;
    }
    if (line.indexOf(';', start) >= 0) {
      throw malformed(resourceName, lineNumber, line, null);
    }
    fields[SEPARATOR_COUNT] = line.substring(start);
    try {
      final double latitude = Double.parseDouble(fields[4]);
      final double longitude = Double.parseDouble(fields[5]);
      final long population = Long.parseLong(fields[8]);
      return new GazetteerEntry(
          fields[0],
          fields[1],
          fields[2],
          splitList(fields[3]),
          new GeoPoint(latitude, longitude),
          fields[6].isEmpty() ? null : fields[6],
          splitList(fields[7]),
          population,
          fields[9].isEmpty() ? null : fields[9],
          parseAttributes(fields[10], fields[0]));
    } catch (IllegalArgumentException e) {
      // Includes NumberFormatException from the numeric fields and the record validations.
      throw malformed(resourceName, lineNumber, line, e);
    }
  }

  /**
   * Splits a pipe separated list field. An empty field yields an empty list, and empty elements
   * from a leading, trailing, or doubled pipe surface through {@link GazetteerEntry}'s
   * validation.
   *
   * @return The list of field values, never {@code null}.
   */
  private static List<String> splitList(String field) {
    if (field.isEmpty()) {
      return List.of();
    }
    final List<String> values = new ArrayList<>();
    int start = 0;
    while (true) {
      final int pipe = field.indexOf('|', start);
      if (pipe < 0) {
        values.add(field.substring(start));
        return values;
      }
      values.add(field.substring(start, pipe));
      start = pipe + 1;
    }
  }

  /**
   * Parses the attributes field of pipe separated {@code key=value} pairs, taking {@code source}
   * as each attribute's provenance.
   *
   * @return The attribute map keyed by attribute name, never {@code null}.
   * @throws IllegalArgumentException Thrown if a pair is not {@code key=value} or a key repeats.
   */
  private static Map<String, AttributeValue> parseAttributes(String field, String source) {
    if (field.isEmpty()) {
      return Map.of();
    }
    final Map<String, AttributeValue> attributes = new LinkedHashMap<>();
    for (final String pair : splitList(field)) {
      final int equals = pair.indexOf('=');
      if (equals <= 0 || equals == pair.length() - 1) {
        throw new IllegalArgumentException("Attribute pair must have the form key=value, got: "
            + pair);
      }
      final String key = pair.substring(0, equals);
      if (attributes.putIfAbsent(key,
          new AttributeValue(pair.substring(equals + 1), source, "")) != null) {
        throw new IllegalArgumentException("Duplicate attribute key: " + key);
      }
    }
    return attributes;
  }

  /**
   * Builds the exception for a malformed row, naming the resource and line and chaining the
   * given cause when present.
   *
   * @return The exception to throw for the malformed row.
   */
  private static InvalidFormatException malformed(String resourceName, int lineNumber,
                                                  String line, Throwable cause) {
    final String message = "Malformed gazetteer data in " + resourceName + " at line "
        + lineNumber + ": " + line;
    return cause == null
        ? new InvalidFormatException(message) : new InvalidFormatException(message, cause);
  }

  /**
   * Reads and parses the bundled data resource.
   *
   * @return The parsed entries in file order.
   * @throws IllegalStateException Thrown if the bundled data resource is missing.
   * @throws UncheckedIOException Thrown if the resource cannot be read or a row is malformed;
   *     for a malformed row the cause names the resource and line.
   */
  private static List<GazetteerEntry> load() {
    try (InputStream in = BundledGazetteer.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing gazetteer data resource: " + RESOURCE);
      }
      return parse(in, RESOURCE);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to load gazetteer data resource " + RESOURCE, e);
    }
  }

  /**
   * Holds the shared instance, initialized on first access to {@link #getInstance()} by the
   * class loader without locking.
   */
  private static final class Holder {
    static final BundledGazetteer INSTANCE = new BundledGazetteer(load());
  }
}
