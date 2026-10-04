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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.geo.Gazetteer;
import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.geo.GeoPoint;
import opennlp.tools.util.InvalidFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the one name-matching rule shared by every gazetteer of this module: names and queries
 * are folded through the same chain (NFC, case fold under {@code Locale.ROOT}, accent fold,
 * UAX #29 word tokens joined by one space), so the same mention resolves the same way whichever
 * gazetteer answers, including through an {@link OverlayGazetteer}.
 */
public class GazetteerFoldingRuleTest {

  /** Capital I with dot above (U+0130), the Turkish dotted capital. */
  private static final String DOTTED_CAPITAL_I = "İ";

  /** Deseret capital long I (U+10400), a supplementary-plane letter with a lowercase form. */
  private static final String DESERET_CAPITAL = new String(Character.toChars(0x10400));

  /** Deseret small long I (U+10428), the lowercase of {@link #DESERET_CAPITAL}. */
  private static final String DESERET_SMALL = new String(Character.toChars(0x10428));

  private static final String SAO_PAULO = "São Paulo";

  private static final List<GazetteerEntry> ENTRIES = List.of(
      entry("ny", "New York", List.of("NYC")),
      entry("sp", SAO_PAULO, List.of()),
      entry("ist", "Istanbul", List.of("Constantinople")),
      entry("des", "Deseret " + DESERET_CAPITAL, List.of()));

  private static GazetteerEntry entry(String id, String name, List<String> alternates) {
    return new GazetteerEntry("fixture", id, name, alternates, new GeoPoint(1.0, 2.0), "US",
        List.of(), 1000L, GazetteerEntry.FEATURE_CLASS_CITY, Map.of());
  }

  private static InputStream utf8(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  private static String userTable() {
    final StringBuilder table = new StringBuilder();
    for (final GazetteerEntry e : ENTRIES) {
      table.append(e.recordId()).append('\t').append(e.name()).append('\t')
          .append(String.join("|", e.alternateNames()))
          .append("\t1.0\t2.0\tUS\tCITY\t1000\n");
    }
    return table.toString();
  }

  private static String geoNamesTable() {
    final StringBuilder table = new StringBuilder();
    for (final GazetteerEntry e : ENTRIES) {
      // The ASCII-name column repeats the name, so an accent-free query can only hit by folding.
      table.append(String.join("\t", e.recordId(), e.name(), e.name(),
          String.join(",", e.alternateNames()), "1.0", "2.0", "P", "PPL", "US", "", "", "", "",
          "", "1000", "", "", "Etc/UTC", "2026-01-01")).append('\n');
    }
    return table.toString();
  }

  private static String overtureTable() {
    final StringBuilder table = new StringBuilder("# synthetic fixture\n");
    for (final GazetteerEntry e : ENTRIES) {
      table.append(String.join("\t", e.recordId(), e.name(),
          String.join(",", e.alternateNames()), "1.0", "2.0", "US", "locality", "1000"))
          .append('\n');
    }
    return table.toString();
  }

  static Stream<Arguments> gazetteers() throws IOException {
    final Gazetteer user = UserGazetteer.load(utf8(userTable()), "customer");
    final Gazetteer geoNames = GeoNamesGazetteer.load(utf8(geoNamesTable()));
    return Stream.of(
        Arguments.of("BundledGazetteer", new BundledGazetteer(ENTRIES)),
        Arguments.of("InMemoryGazetteer", InMemoryGazetteer.fromEntries(ENTRIES)),
        Arguments.of("UserGazetteer", user),
        Arguments.of("GeoNamesGazetteer", geoNames),
        Arguments.of("OvertureGazetteer", OvertureGazetteer.load(utf8(overtureTable()))),
        Arguments.of("OverlayGazetteer", new OverlayGazetteer(geoNames, user, List.of())));
  }

  static Stream<Arguments> queries() {
    return Stream.of(
        Arguments.of("New\nYork", "ny"),
        Arguments.of("new  york", "ny"),
        Arguments.of("New York", "ny"),
        Arguments.of("nyc", "ny"),
        Arguments.of("Sao Paulo", "sp"),
        Arguments.of("SÃO PAULO", "sp"),
        Arguments.of("São Paulo", "sp"),
        Arguments.of(DOTTED_CAPITAL_I + "STANBUL", "ist"),
        Arguments.of("istanbul", "ist"),
        Arguments.of("constantinople", "ist"),
        Arguments.of("deseret " + DESERET_SMALL, "des"));
  }

  static Stream<Arguments> gazetteersAndQueries() throws IOException {
    return gazetteers().flatMap(g -> queries().map(q ->
        Arguments.of(g.get()[0], g.get()[1], q.get()[0], q.get()[1])));
  }

  @ParameterizedTest(name = "{0}: {2}")
  @MethodSource("gazetteersAndQueries")
  void testEveryGazetteerFoldsQueriesTheSameWay(String kind, Gazetteer gazetteer,
                                                String query, String expectedId)
      throws IOException {
    final List<GazetteerEntry> found = gazetteer.lookup(query);
    assertTrue(!found.isEmpty(), () -> kind + " misses " + query);
    for (final GazetteerEntry entry : found) {
      assertEquals(expectedId, entry.recordId(), kind);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("gazetteers")
  void testQueryWithoutWordTokensMatchesNothing(String kind, Gazetteer gazetteer)
      throws IOException {
    assertTrue(gazetteer.lookup("...").isEmpty(), kind);
    assertTrue(gazetteer.lookup("").isEmpty(), kind);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("gazetteers")
  void testExactIdLookupIsNotFolded(String kind, Gazetteer gazetteer) throws IOException {
    final GazetteerEntry ny = gazetteer.lookup("New York").get(0);
    assertEquals("ny", gazetteer.byId(ny.source(), "ny").get().recordId(), kind);
    assertTrue(gazetteer.byId(ny.source(), "NY").isEmpty(), kind);
  }

  /** The overlay answers once from each side, with the same folded key on both. */
  @Test
  void testOverlayResolvesOneMentionOneWay() throws IOException {
    final Gazetteer base = GeoNamesGazetteer.load(utf8(geoNamesTable()));
    final Gazetteer additions = UserGazetteer.load(utf8(userTable()), "customer");
    final OverlayGazetteer overlay = new OverlayGazetteer(base, additions, List.of());
    for (final String query : List.of("Sao Paulo", "New\nYork", "new  york")) {
      final List<GazetteerEntry> found = overlay.lookup(query);
      assertEquals(2, found.size(), query);
      assertEquals("customer", found.get(0).source(), query);
      assertEquals(GeoNamesGazetteer.SOURCE, found.get(1).source(), query);
    }
  }

  private static final String NO_WORD = "...";

  private static String geoNamesRow(String id, String name, String alternates) {
    return String.join("\t", id, name, name, alternates, "1.0", "2.0", "P", "PPL", "US", "", "",
        "", "", "", "1", "", "", "Etc/UTC", "2026-01-01") + "\n";
  }

  private static String overtureRow(String id, String name, String alternates) {
    return String.join("\t", id, name, alternates, "1.0", "2.0", "US", "locality", "1") + "\n";
  }

  private static String userRow(String id, String name, String alternates) {
    return String.join("\t", id, name, alternates, "1.0", "2.0", "US", "CITY", "1") + "\n";
  }

  static Stream<Arguments> loadersWithAnUnmatchableName() {
    return Stream.of(
        Arguments.of("UserGazetteer", (Loader) in -> UserGazetteer.load(in, "customer"),
            userRow("ok", "Okay", "") + userRow("bad", NO_WORD, "")),
        Arguments.of("GeoNamesGazetteer", (Loader) GeoNamesGazetteer::load,
            geoNamesRow("1", "Okay", "") + geoNamesRow("2", NO_WORD, "")),
        Arguments.of("OvertureGazetteer", (Loader) OvertureGazetteer::load,
            overtureRow("d1", "Okay", "") + overtureRow("d2", NO_WORD, "")),
        Arguments.of("UserGazetteer, alternate name",
            (Loader) in -> UserGazetteer.load(in, "customer"),
            userRow("ok", "Okay", "") + userRow("bad", "Fine", NO_WORD)));
  }

  /**
   * A canonical name that folds to an empty key can never be queried, so every loader rejects
   * the row and names its line, like the bundled table does. A hand-written user file is held
   * to the same standard for its alternate names.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("loadersWithAnUnmatchableName")
  void testLoadersRejectANameWithoutWordTokens(String kind, Loader loader, String table) {
    final InvalidFormatException e =
        assertThrows(InvalidFormatException.class, () -> loader.load(utf8(table)), kind);
    assertTrue(e.getMessage().contains("line 2"), kind + ": " + e.getMessage());
    assertTrue(e.getMessage().contains("folds to an empty match key"),
        kind + ": " + e.getMessage());
  }

  static Stream<Arguments> downloadedTablesWithAnUnmatchableAlternate() {
    return Stream.of(
        Arguments.of("GeoNamesGazetteer", (Loader) GeoNamesGazetteer::load,
            geoNamesRow("1", "Okay", NO_WORD + ",Fine")),
        Arguments.of("OvertureGazetteer", (Loader) OvertureGazetteer::load,
            overtureRow("d1", "Okay", NO_WORD + ",Fine")));
  }

  /**
   * A downloaded table is not the user's to edit, so an alternate name that folds to an empty
   * key is left out of the index instead of failing the load; the other names still hit.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("downloadedTablesWithAnUnmatchableAlternate")
  void testDownloadedTablesSkipAnAlternateWithoutWordTokens(String kind, Loader loader,
                                                            String table) throws IOException {
    final Gazetteer gazetteer = loader.load(utf8(table));
    assertEquals(1, gazetteer.lookup("okay").size(), kind);
    assertEquals(1, gazetteer.lookup("fine").size(), kind);
    assertTrue(gazetteer.lookup(NO_WORD).isEmpty(), kind);
  }

  /** A suppression rule written in another spelling hides the base entry through the overlay. */
  @Test
  void testSuppressionFollowsTheFoldingRuleThroughTheOverlay() throws IOException {
    final Gazetteer base = GeoNamesGazetteer.load(utf8(geoNamesTable()));
    final OverlayGazetteer overlay =
        new OverlayGazetteer(base, null, List.of(new Suppression("sao  paulo")));
    assertTrue(overlay.lookup(SAO_PAULO).isEmpty());
    assertEquals(1, overlay.lookup("New York").size());
  }

  /** One of the three stream loaders. */
  @FunctionalInterface
  interface Loader {
    Gazetteer load(InputStream in) throws IOException;
  }
}
