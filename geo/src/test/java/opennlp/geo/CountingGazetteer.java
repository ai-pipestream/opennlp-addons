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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import opennlp.tools.geo.Gazetteer;
import opennlp.tools.geo.GazetteerEntry;

/**
 * A test double that counts {@link #lookup(CharSequence)} calls on the gazetteer it wraps, so
 * a test can pin how many times a geocoder asks for a name.
 */
final class CountingGazetteer implements Gazetteer {

  private final Gazetteer delegate;
  private int lookups;

  /**
   * Wraps a gazetteer.
   *
   * @param delegate The gazetteer that answers every query. Must not be {@code null}.
   */
  CountingGazetteer(Gazetteer delegate) {
    this.delegate = delegate;
  }

  /** {@return the number of {@link #lookup(CharSequence)} calls so far} */
  int lookups() {
    return lookups;
  }

  /** {@inheritDoc} */
  @Override
  public List<GazetteerEntry> lookup(CharSequence name) throws IOException {
    lookups++;
    return delegate.lookup(name);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<GazetteerEntry> byId(String source, String recordId) throws IOException {
    return delegate.byId(source, recordId);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<GazetteerEntry> byRegion(String isoCountryCode) throws IOException {
    return delegate.byRegion(isoCountryCode);
  }

  /** {@inheritDoc} */
  @Override
  public Set<String> sources() {
    return delegate.sources();
  }
}
