<!--
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

# Document region voting

`DocumentRegionAnnotator` turns the resolved locations of a document into a
ballot of countries: every location entity resolved under
`GeocodeAnnotator.LOCATIONS` votes for its entry's country weighted by the
resolution confidence, an unresolved location entity that is an English
country name votes for that country directly, and a country flag emoji votes
for its country. The ballot is a document-scoped layer of `RegionVote` rows
ranked by share, and `DocumentRegionAnnotator.winner` elects a country only
when its lead over the runner-up reaches a caller-chosen margin.

## Modules

`geo` supplies the region ballot, gazetteer, and geocoder contracts. `numeric`
contains `RegionAwareMoneyAnnotator`, which picks the currency symbol table of
the winning country, and depends on `geo`. The immutable `Document` container
and typed layer contracts come from core.

Run `GeocodeAnnotator` first, then `DocumentRegionAnnotator`. Both the entities
and locations layers must be present, though they may be empty.
