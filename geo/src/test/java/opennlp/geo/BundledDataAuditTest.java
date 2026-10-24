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
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.util.StringUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Audits the bundled naturalearth-populated-places.txt table: the claims made in the data file
 * header and the class javadoc are enforced here, row by row, so a regeneration of the table
 * cannot silently drift from them.
 */
public class BundledDataAuditTest {

  private static final String RESOURCE = "naturalearth-populated-places.txt";

  /** Attribute keys the derivation may emit. Add new derivation keys here. */
  private static final Set<String> ALLOWED_ATTRIBUTE_KEYS = Set.of(
      GazetteerEntry.ATTRIBUTE_KEY_WIKIDATA,
      GazetteerEntry.ATTRIBUTE_KEY_GEONAMES,
      GazetteerEntry.ATTRIBUTE_KEY_WHOSONFIRST);

  /** The header sentence that states the row count, for example {@code 7343 rows.}. */
  private static final String ROW_COUNT_CLAIM = " rows.";

  /** The prefix of a Scottish or Irish surname, where a capital after it is regular casing. */
  private static final String MC_PREFIX = "Mc";

  /** Values the case-anomaly check flags that are verified correct upstream spellings. */
  private static final Set<String> LEGIT_CASE_ANOMALIES =
      Set.of("KwaZulu-Natal", "HaMerkaz", "HaDarom", "HaZafon");

  private static List<GazetteerEntry> entries;

  @BeforeAll
  static void parseBundledTable() throws IOException {
    try (InputStream in = BundledGazetteer.class.getResourceAsStream(RESOURCE)) {
      assertNotNull(in, "Missing bundled data resource: " + RESOURCE);
      // Parsing the whole resource verifies every row and reports the first malformed one.
      entries = BundledGazetteer.parse(in, RESOURCE);
    }
  }

  @Test
  void testRowCountMatchesTheHeaderClaimExactly() throws IOException {
    // The header states the row count; a regeneration with a stale header fails here.
    Integer claimed = null;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        BundledGazetteer.class.getResourceAsStream(RESOURCE), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null && line.startsWith("#")) {
        final int end = line.indexOf(ROW_COUNT_CLAIM);
        int start = end;
        while (start > 0 && StringUtil.isAsciiDigit(line.charAt(start - 1))) {
          start--;
        }
        if (end > 0 && start < end) {
          claimed = Integer.parseInt(line.substring(start, end));
          break;
        }
      }
    }
    assertNotNull(claimed, "The data file header must state its row count as '<n> rows.'");
    assertEquals(claimed, entries.size(), "Header row-count claim does not match the data");
  }

  @Test
  void testResourceIsPureAscii() throws IOException {
    try (InputStream in = BundledGazetteer.class.getResourceAsStream(RESOURCE)) {
      assertNotNull(in);
      final byte[] bytes = in.readAllBytes();
      for (int i = 0; i < bytes.length; i++) {
        final int unsigned = bytes[i] & 0xFF;
        assertTrue(unsigned < 0x80, "Non-ASCII byte 0x" + Integer.toHexString(unsigned)
            + " at offset " + i);
      }
    }
  }

  @Test
  void testNoValueCarriesMojibakeArtifacts() {
    // Letter-level corruption from the upstream distribution (see the data file's mojibake
    // note) must never survive a regeneration: no replacement characters, no '?' damage
    // markers, and no case-anomaly artifacts outside the verified allowlist.
    for (final GazetteerEntry entry : entries) {
      assertNoArtifacts(entry.name(), "name", entry);
      for (final String alternateName : entry.alternateNames()) {
        assertNoArtifacts(alternateName, "alternate name", entry);
      }
      for (final String level : entry.containment()) {
        assertNoArtifacts(level, "containment", entry);
      }
    }
  }

  private static void assertNoArtifacts(String value, String what, GazetteerEntry entry) {
    assertFalse(value.indexOf('\uFFFD') >= 0,
        "Replacement character in " + what + " '" + value + "' of " + entry.recordId());
    assertFalse(value.indexOf('?') >= 0,
        "Damage marker '?' in " + what + " '" + value + "' of " + entry.recordId());
    if (LEGIT_CASE_ANOMALIES.contains(value)) {
      return;
    }
    if (hasCaseAnomaly(withoutMcPrefixes(value))) {
      fail("Mojibake case anomaly in " + what + " '" + value + "' of " + entry.recordId());
    }
  }

  /**
   * Detects the upstream mojibake signature in the pure ASCII table: an uppercase letter directly
   * after a lowercase one, or a word starting with two uppercase letters followed by a lowercase
   * one, as in {@code MUdenine} for Medenine, where an accented letter was replaced by a wrong
   * ASCII letter.
   */
  private static boolean hasCaseAnomaly(String value) {
    for (int i = 0; i + 1 < value.length(); i++) {
      final char c = value.charAt(i);
      final char next = value.charAt(i + 1);
      if (StringUtil.isAsciiLowerCase(c) && isAsciiUpperCase(next)) {
        return true;
      }
      if (atWordStart(value, i) && i + 2 < value.length() && isAsciiUpperCase(c)
          && isAsciiUpperCase(next) && StringUtil.isAsciiLowerCase(value.charAt(i + 2))) {
        return true;
      }
    }
    return false;
  }

  /** Removes every {@code Mc} that starts a word and precedes a capital, so {@code McAllen} passes. */
  private static String withoutMcPrefixes(String value) {
    final StringBuilder stripped = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      if (atWordStart(value, i) && value.startsWith(MC_PREFIX, i)
          && i + MC_PREFIX.length() < value.length()
          && isAsciiUpperCase(value.charAt(i + MC_PREFIX.length()))) {
        i += MC_PREFIX.length() - 1;
      } else {
        stripped.append(value.charAt(i));
      }
    }
    return stripped.toString();
  }

  /** {@return {@code true} if no ASCII letter, digit or underscore precedes position {@code i}} */
  private static boolean atWordStart(String value, int i) {
    if (i == 0) {
      return true;
    }
    final char before = value.charAt(i - 1);
    return !StringUtil.isAsciiLetter(before) && !StringUtil.isAsciiDigit(before) && before != '_';
  }

  /** {@return {@code true} if {@code c} is an ASCII capital letter} */
  private static boolean isAsciiUpperCase(char c) {
    return StringUtil.isAsciiLetter(c) && !StringUtil.isAsciiLowerCase(c);
  }

  @Test
  void testEveryNameFoldsToANonEmptyMatchKey() {
    // Check each name directly so this audit does not depend on indexing order.
    for (final GazetteerEntry entry : entries) {
      assertFalse(GazetteerIndex.foldKey(entry.name()).isEmpty(),
          "Name folds to an empty match key: " + entry.recordId());
      for (final String alternateName : entry.alternateNames()) {
        assertFalse(GazetteerIndex.foldKey(alternateName).isEmpty(),
            "Alternate name '" + alternateName + "' folds to an empty match key: "
                + entry.recordId());
      }
    }
  }

  @Test
  void testNoDuplicateSourceAndRecordId() {
    final Set<String> seen = new HashSet<>(entries.size() * 2);
    for (final GazetteerEntry entry : entries) {
      assertTrue(seen.add(entry.source() + ";" + entry.recordId()),
          "Duplicate record: " + entry.source() + ";" + entry.recordId());
    }
  }

  @Test
  void testEveryRowKeepsTheDocumentedShape() {
    for (final GazetteerEntry entry : entries) {
      // Source, feature class, and containment shape are fixed for this table.
      assertEquals("naturalearth", entry.source(), entry.recordId());
      assertEquals(GazetteerEntry.FEATURE_CLASS_CITY, entry.featureClass(), entry.recordId());
      assertTrue(entry.containment().size() <= 1,
          "v1 has at most the admin-1 containment element: " + entry.recordId());
      // GazetteerEntry and GeoPoint validate these at construction; asserted here so the audit
      // stands on its own if those validations ever loosen.
      assertTrue(entry.population() >= 0, entry.recordId());
      final double latitude = entry.location().latitude();
      final double longitude = entry.location().longitude();
      assertTrue(latitude >= -90.0 && latitude <= 90.0, entry.recordId());
      assertTrue(longitude >= -180.0 && longitude <= 180.0, entry.recordId());
      final String countryCode = entry.countryCode();
      if (countryCode != null) {
        assertTrue(countryCode.length() == 2
                && countryCode.charAt(0) >= 'A' && countryCode.charAt(0) <= 'Z'
                && countryCode.charAt(1) >= 'A' && countryCode.charAt(1) <= 'Z',
            "Malformed country code " + countryCode + " on " + entry.recordId());
      }
      for (final String key : entry.attributes().keySet()) {
        assertTrue(ALLOWED_ATTRIBUTE_KEYS.contains(key),
            "Unexpected attribute key " + key + " on " + entry.recordId());
      }
    }
  }

  @Test
  void testMostRowsCarryACountryCode() {
    // Only the upstream -99 sentinel rows (disputed territories) may lack a code.
    final long withoutCode = entries.stream().filter(e -> e.countryCode() == null).count();
    assertTrue(withoutCode <= 20, "Too many rows without a country code: " + withoutCode);
  }

  @Test
  void testBundledInstanceLoadsAndIndexesTheTable() {
    final BundledGazetteer gazetteer = BundledGazetteer.getInstance();
    assertEquals(Set.of("naturalearth"), gazetteer.sources());
    final List<GazetteerEntry> tokyo = gazetteer.lookup("Tokyo");
    assertFalse(tokyo.isEmpty());
    assertEquals("JP", tokyo.get(0).countryCode());
    assertEquals("1159151609", tokyo.get(0).recordId());
  }

  @Test
  void testAccentedQueryFindsAsciiRow() {
    // Z + u-with-diaeresis + rich against the ASCII "Zurich" row.
    final String query = "Z" + new String(Character.toChars(0x00FC)) + "rich";
    final List<GazetteerEntry> result = BundledGazetteer.getInstance().lookup(query);
    assertFalse(result.isEmpty(), "Accented query should find the ASCII row");
    assertEquals("CH", result.get(0).countryCode());
    assertEquals("Zurich", result.get(0).name());
  }

  @Test
  void testRepairedNamesAreFindableByTheirCorrectSpelling() {
    // Rows whose upstream names are damaged (see the data file's mojibake note) must be
    // findable by the verified spelling, and the damaged forms must be gone.
    final BundledGazetteer gazetteer = BundledGazetteer.getInstance();
    assertEquals("1159112843",
        gazetteer.lookup("Sidi Bouzid").get(0).recordId()); // upstream 'Sdid Bouzid'
    assertEquals("1159146123", gazetteer.lookup("Amundsen-Scott South Pole Station")
        .get(0).recordId()); // upstream NAMEASCII 'AmundseniScott South Pole Station'
    assertTrue(gazetteer.lookup("Sdid Bouzid").isEmpty());
    assertTrue(gazetteer.lookup("AmundseniScott South Pole Station").isEmpty());
    // A repaired containment value, upstream 'MUdenine'.
    assertEquals(List.of("Medenine"),
        gazetteer.byId("naturalearth", "1159112749").orElseThrow().containment());
  }

  @ParameterizedTest
  @ValueSource(strings = {"US", "DE", "FR", "JP", "GB", "BR", "IN", "CN"})
  void testByRegionServesTheRegionJoinKeyShape(String code) {
    // ISO 3166-1 alpha-2 codes are the region join key of the Gazetteer interface (a flag emoji
    // decodes to the same code, for example). byRegion must answer for them, and the returned
    // entry must carry the very code it was asked for.
    final GazetteerEntry entry = BundledGazetteer.getInstance().byRegion(code).orElseThrow(
        () -> new AssertionError("No entry for region " + code));
    assertEquals(code, entry.countryCode());
  }

  @Test
  void testByRegionReturnsTheMostPopulousEntry() {
    // Documented representative choice: for JP that is Tokyo, for US New York.
    final BundledGazetteer gazetteer = BundledGazetteer.getInstance();
    assertEquals("Tokyo", gazetteer.byRegion("JP").orElseThrow().name());
    assertEquals("New York", gazetteer.byRegion("US").orElseThrow().name());
  }

  @Test
  void testLookupIsDeterministicAcrossCalls() {
    final BundledGazetteer gazetteer = BundledGazetteer.getInstance();
    assertEquals(gazetteer.lookup("Springfield"), gazetteer.lookup("Springfield"));
    assertEquals(gazetteer.lookup("San Jose"), gazetteer.lookup("San Jose"));
  }
}
