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
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import opennlp.tools.commons.ThreadSafe;
import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.StringUtil;
import opennlp.tools.util.normalizer.Term;
import opennlp.tools.util.normalizer.TermAnalyzer;

/**
 * The one in-memory index behind every {@link opennlp.tools.geo.Gazetteer} of this module:
 * entries keyed by the folded form of every name variant, by (source, record id), and by
 * country, where the country representative is the first entry in
 * {@link CandidateRanking#BY_PRIOR} order.
 *
 * <p>Names and queries share one folding rule, {@link #foldKey(CharSequence)}: NFC, case fold
 * under {@link Locale#ROOT}, accent fold, then the
 * <a href="https://unicode.org/reports/tr29/">UAX&#160;#29</a> word tokens joined by one
 * space. Every name is folded once, when it is indexed; a lookup folds only the query. A name
 * that folds to an empty key is rejected because no query could ever reach it.</p>
 *
 * <p>Instances are immutable and thread-safe; a {@link Builder} collects entries, and
 * {@link #load(InputStream, boolean, RowParser)} is the shared read loop of the file loaders.</p>
 */
@ThreadSafe
final class GazetteerIndex {

  /** Parses one data line of a gazetteer table into an entry. */
  @FunctionalInterface
  interface RowParser {

    /**
     * Parses one data line.
     *
     * @param line       The data line; never blank and never a skipped comment line.
     * @param lineNumber The one-based line number, for format-error messages.
     * @return The parsed entry. Never {@code null}.
     * @throws InvalidFormatException Thrown if the line is not a valid row.
     */
    GazetteerEntry parse(String line, int lineNumber) throws InvalidFormatException;
  }

  /**
   * The folding chain shared by indexed names and queries. {@code caseFold()} lower-cases under
   * {@link Locale#ROOT}, so the index does not depend on the JVM's default locale: the Turkish
   * dotted capital I (U+0130) folds to {@code i} like the ASCII capital, while a dotless
   * {@code ı} (U+0131) stays distinct. Stateless and thread-safe.
   */
  private static final TermAnalyzer FOLD =
      TermAnalyzer.builder().nfc().caseFold().accentFold().build();

  private final Map<String, List<GazetteerEntry>> byName;
  private final Map<IdKey, GazetteerEntry> byId;
  private final Map<String, GazetteerEntry> byCountry;
  private final Set<String> sources;

  /**
   * Freezes the builder's maps: every candidate list is ranked by the population prior and
   * copied into an immutable list.
   *
   * @param builder The builder whose entries this index serves. Must not be {@code null}.
   */
  private GazetteerIndex(Builder builder) {
    final Map<String, List<GazetteerEntry>> ranked = new HashMap<>(builder.byName.size() * 2);
    for (final Map.Entry<String, List<GazetteerEntry>> indexed : builder.byName.entrySet()) {
      final List<GazetteerEntry> candidates = indexed.getValue();
      candidates.sort(CandidateRanking.BY_PRIOR);
      ranked.put(indexed.getKey(), List.copyOf(candidates));
    }
    this.byName = ranked;
    this.byId = Map.copyOf(builder.byId);
    this.byCountry = Map.copyOf(builder.byCountry);
    this.sources = Set.copyOf(builder.sources);
  }

  /**
   * Indexes caller-supplied entries, the path of the bundled and in-memory gazetteers.
   *
   * @param entries The entries to index. Must not be {@code null} or contain {@code null}.
   * @return The index over the entries.
   * @throws IllegalArgumentException Thrown if {@code entries} is {@code null}, contains a
   *     {@code null} element, contains two entries with the same (source, recordId), or
   *     contains an entry with a name that folds to an empty match key.
   */
  static GazetteerIndex of(List<GazetteerEntry> entries) {
    if (entries == null) {
      throw new IllegalArgumentException("entries must not be null");
    }
    final Builder builder = new Builder();
    for (final GazetteerEntry entry : entries) {
      if (entry == null) {
        throw new IllegalArgumentException("entries must not contain a null element");
      }
      if (!builder.add(entry)) {
        throw new IllegalArgumentException("Duplicate gazetteer record for source "
            + entry.source() + " and recordId " + entry.recordId());
      }
    }
    return builder.build();
  }

  /**
   * Reads a table into an index: every line is passed to {@code parser} with its one-based
   * line number, except blank lines and, when {@code skipComments} is set, lines starting with
   * {@code #}. A byte order mark at the start of the content is not part of the first line.
   *
   * @param in           The table content, read fully as UTF-8 but not closed.
   * @param skipComments Whether lines starting with {@code #} are skipped.
   * @param parser       The row parser of the caller's table format.
   * @return The index over the parsed entries.
   * @throws IOException Thrown if reading fails.
   * @throws InvalidFormatException Thrown if the content has no data rows, a row repeats a
   *     record id, a name folds to an empty match key, or from {@code parser} for a malformed
   *     row.
   */
  static GazetteerIndex load(InputStream in, boolean skipComments, RowParser parser)
      throws IOException {
    final Builder builder = new Builder();
    final BufferedReader reader = utf8Reader(in);
    String line;
    int lineNumber = 0;
    while ((line = reader.readLine()) != null) {
      lineNumber++;
      if (lineNumber == 1) {
        line = StringUtil.stripByteOrderMark(line);
      }
      if (StringUtil.isUnicodeBlank(line) || (skipComments && line.charAt(0) == '#')) {
        continue;
      }
      final GazetteerEntry entry = parser.parse(line, lineNumber);
      final boolean added;
      try {
        added = builder.add(entry);
      } catch (IllegalArgumentException e) {
        throw new InvalidFormatException("line " + lineNumber + ": " + e.getMessage(), e);
      }
      if (!added) {
        throw new InvalidFormatException(
            "line " + lineNumber + " repeats record id: " + entry.recordId());
      }
    }
    if (builder.isEmpty()) {
      throw new InvalidFormatException("the table contains no rows");
    }
    return builder.build();
  }

  /**
   * Finds the candidates indexed under a name.
   *
   * @param name The name to look up, folded like the indexed names. Must not be {@code null}.
   * @return The candidates in {@link CandidateRanking#BY_PRIOR} order, as an immutable list;
   *     empty when nothing matches or {@code name} has no word token.
   */
  List<GazetteerEntry> lookup(CharSequence name) {
    final String key = foldKey(name);
    if (key.isEmpty()) {
      return List.of();
    }
    final List<GazetteerEntry> found = byName.get(key);
    return found == null ? List.of() : found;
  }

  /**
   * Finds the entry with a source-scoped record id; neither part is folded.
   *
   * @param source   The dataset identifier. Must not be {@code null}.
   * @param recordId The record id within that dataset. Must not be {@code null}.
   * @return The entry, or empty when no entry has that id.
   */
  Optional<GazetteerEntry> byId(String source, String recordId) {
    return Optional.ofNullable(byId.get(new IdKey(source, recordId)));
  }

  /**
   * Finds the first entry of a country in {@link CandidateRanking#BY_PRIOR} order.
   *
   * @param isoCountryCode The <a href="https://www.iso.org/iso-3166-country-codes.html">ISO
   *                       3166-1</a> alpha-2 code, two ASCII letters of either case. Must
   *                       not be {@code null}.
   * @return The most populous entry, or empty when the code is well-formed but unknown.
   * @throws IllegalArgumentException Thrown if {@code isoCountryCode} is {@code null} or is not
   *     two ASCII letters.
   */
  Optional<GazetteerEntry> byRegion(String isoCountryCode) {
    return Optional.ofNullable(byCountry.get(normalizeRegionCode(isoCountryCode)));
  }

  /** {@return the dataset identifiers of the indexed entries, as an immutable set} */
  Set<String> sources() {
    return sources;
  }

  /**
   * Folds one name or query to its match key: UAX&#160;#29 word tokens, each NFC + case fold +
   * accent fold, joined by single spaces. Line breaks, repeated spaces and any other Unicode
   * whitespace between the tokens fold away with the segmentation.
   *
   * @param name The name to fold. Must not be {@code null}.
   * @return The match key, or empty when the name has no word token.
   */
  static String foldKey(CharSequence name) {
    final List<Term> terms = FOLD.analyze(name);
    if (terms.isEmpty()) {
      return "";
    }
    final StringBuilder key = new StringBuilder(name.length());
    for (final Term term : terms) {
      if (key.length() > 0) {
        key.append(' ');
      }
      key.append(term.normalized());
    }
    return key.toString();
  }

  /**
   * Validates an ISO 3166-1 alpha-2 region code and folds it to its canonical uppercase form.
   *
   * @param isoCountryCode The code to validate. Must not be {@code null}.
   * @return The code with both letters upper-cased.
   * @throws IllegalArgumentException Thrown if {@code isoCountryCode} is {@code null} or is not
   *     two ASCII letters.
   */
  static String normalizeRegionCode(String isoCountryCode) {
    if (isoCountryCode == null) {
      throw new IllegalArgumentException("isoCountryCode must not be null");
    }
    if (isoCountryCode.length() != 2
        || !isAsciiLetter(isoCountryCode.charAt(0)) || !isAsciiLetter(isoCountryCode.charAt(1))) {
      throw new IllegalArgumentException(
          "isoCountryCode must be an ISO 3166-1 alpha-2 code (two ASCII letters), got: "
              + isoCountryCode);
    }
    return new String(new char[] {upperAscii(isoCountryCode.charAt(0)),
        upperAscii(isoCountryCode.charAt(1))});
  }

  /**
   * Splits a value at each occurrence of a separator and retains empty fields, including a final
   * empty field.
   *
   * @param value The value to split. Must not be {@code null}.
   * @param separator The separator character.
   * @return The fields in input order.
   * @throws IllegalArgumentException Thrown if {@code value} is {@code null}.
   */
  static String[] split(String value, char separator) {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    int fieldCount = 1;
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) == separator) {
        fieldCount++;
      }
    }
    final String[] fields = new String[fieldCount];
    int field = 0;
    int start = 0;
    for (int i = 0; i <= value.length(); i++) {
      if (i == value.length() || value.charAt(i) == separator) {
        fields[field++] = value.substring(start, i);
        start = i + 1;
      }
    }
    return fields;
  }

  /**
   * Creates a reader that reports malformed UTF-8 instead of inserting replacement characters.
   * Closing the reader closes the input stream.
   *
   * @param in The input stream. Must not be {@code null}.
   * @return A buffered strict UTF-8 reader.
   * @throws IllegalArgumentException Thrown if {@code in} is {@code null}.
   */
  static BufferedReader utf8Reader(InputStream in) {
    if (in == null) {
      throw new IllegalArgumentException("in must not be null");
    }
    return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)));
  }

  /** {@return {@code true} if {@code c} is an ASCII letter} */
  private static boolean isAsciiLetter(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
  }

  /** {@return {@code c} upper-cased if it is an ASCII lowercase letter, otherwise unchanged} */
  private static char upperAscii(char c) {
    return c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c;
  }

  /** The composite identifier of one record; only (source, recordId) together are unique. */
  private record IdKey(String source, String recordId) {
  }

  /** Collects entries for one {@link GazetteerIndex}; not thread-safe, used by one loader. */
  static final class Builder {

    private final Map<String, List<GazetteerEntry>> byName = new HashMap<>();
    private final Map<IdKey, GazetteerEntry> byId = new HashMap<>();
    private final Map<String, GazetteerEntry> byCountry = new HashMap<>();
    private final Set<String> sources = new HashSet<>();

    /** Starts an empty builder. */
    private Builder() {
    }

    /**
     * Indexes one entry under the folded form of its canonical and alternate names, its
     * (source, record id), and, when it has a country code, as that country's candidate
     * representative in {@link CandidateRanking#BY_PRIOR} order.
     *
     * @param entry The entry to index. Must not be {@code null}.
     * @return {@code true} if the entry was added, or {@code false} if its (source, record id)
     *     was present; a rejected entry changes nothing.
     * @throws IllegalArgumentException Thrown if a name of {@code entry} folds to an empty
     *     match key, which would leave the record unreachable by lookup.
     */
    boolean add(GazetteerEntry entry) {
      final IdKey id = new IdKey(entry.source(), entry.recordId());
      if (byId.containsKey(id)) {
        return false;
      }
      final String[] keys = new String[entry.alternateNames().size() + 1];
      keys[0] = requireMatchable(entry.name(), entry);
      for (int i = 1; i < keys.length; i++) {
        keys[i] = requireMatchable(entry.alternateNames().get(i - 1), entry);
      }
      byId.put(id, entry);
      sources.add(entry.source());
      for (final String key : keys) {
        final List<GazetteerEntry> entries =
            byName.computeIfAbsent(key, unused -> new ArrayList<>(2));
        // An entry whose names fold to the same key is listed once.
        if (!entries.contains(entry)) {
          entries.add(entry);
        }
      }
      if (entry.countryCode() != null) {
        byCountry.merge(entry.countryCode(), entry, (existing, candidate) ->
            CandidateRanking.BY_PRIOR.compare(candidate, existing) < 0 ? candidate : existing);
      }
      return true;
    }

    /** {@return {@code true} if nothing was added} */
    boolean isEmpty() {
      return byId.isEmpty();
    }

    /** {@return the immutable index over the added entries, ranked by the population prior} */
    GazetteerIndex build() {
      return new GazetteerIndex(this);
    }

    /**
     * Folds a name and rejects an empty result.
     *
     * @param name  The canonical or alternate name to fold. Must not be {@code null}.
     * @param entry The entry the name belongs to, named in the failure message.
     * @return The non-empty match key of {@code name}.
     * @throws IllegalArgumentException Thrown if {@code name} folds to an empty match key.
     */
    private static String requireMatchable(String name, GazetteerEntry entry) {
      final String key = foldKey(name);
      if (key.isEmpty()) {
        throw new IllegalArgumentException("Name '" + name + "' of record " + entry.source()
            + ";" + entry.recordId() + " folds to an empty match key, so the record would be"
            + " unreachable by lookup");
      }
      return key;
    }
  }
}
