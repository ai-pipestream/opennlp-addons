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

# Gazetteer-backed geocoding annotator

`GeocodeAnnotator` resolves the location entities of a document against a
`Geocoder` and records every resolution, with its gazetteer entry, coordinates
and confidence, under the `GeocodeAnnotator.LOCATIONS` layer. All mentions of a
document go to the geocoder in one call, so an implementation can use the
co-occurring mentions to disambiguate. A mention the geocoder cannot resolve has
no annotation; a mention ranked against several candidates has one annotation
per candidate, best first.

## Modules

The `geo` module provides geocoding and region voting. Run `GeocodeAnnotator`
before `DocumentRegionAnnotator`: the ballot consumes the locations layer and
adds country-name and flag evidence. The `numeric` module supplies the
downstream region-aware currency adapter. Core supplies `Document` and the flag
decoder.
