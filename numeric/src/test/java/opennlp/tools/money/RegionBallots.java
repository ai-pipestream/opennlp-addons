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

package opennlp.tools.money;

import java.util.List;
import java.util.Map;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.geo.DocumentRegionAnnotator;
import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.geo.GeoPoint;
import opennlp.tools.geo.Geocoder;
import opennlp.tools.geo.RegionVote;

/**
 * Shared fixtures of the region-aware money tests: documents carrying a region ballot,
 * minimal gazetteer entries, and a geocoder that must never be consulted.
 */
final class RegionBallots {

  private RegionBallots() {
  }

  /**
   * Builds a document that carries a region ballot with a single full-share vote for
   * one country.
   *
   * @param text The document text. Must not be {@code null}.
   * @param countryCode The winning ISO 3166-1 alpha-2 country code. Must not be
   *                    {@code null}.
   * @return A {@link Document} with a one-row region ballot. Never {@code null}.
   */
  static Document withBallot(String text, String countryCode) {
    return Document.of(text).with(DocumentRegionAnnotator.REGIONS,
        List.of(Annotation.of(new RegionVote(countryCode, 1.0))));
  }

  /**
   * Builds a minimal city entry for a country; the coordinates and population are
   * placeholders because only the country code matters for the region ballot.
   *
   * @param name The city name. Must not be {@code null}.
   * @param countryCode The ISO 3166-1 alpha-2 country code. Must not be {@code null}.
   * @return A {@link GazetteerEntry} for the city. Never {@code null}.
   */
  static GazetteerEntry entry(String name, String countryCode) {
    return new GazetteerEntry("test", name, name, List.of(), new GeoPoint(0.0, 0.0),
        countryCode, List.of(), 1_000_000, GazetteerEntry.FEATURE_CLASS_CITY, Map.of());
  }

  /**
   * Builds a geocoder that must never be consulted: any call fails the test loudly.
   *
   * @return A {@link Geocoder} that rejects every call. Never {@code null}.
   */
  static Geocoder unreachableGeocoder() {
    return (text, mentions) -> {
      throw new IllegalStateException("the geocoder must not be consulted");
    };
  }
}
