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

# Geographic hierarchy annotator

`HierarchyAnnotator` expands every geocoded mention into its containment chain:
the enclosing places from the nearest outward, so a neighbourhood expands to its
borough, city, region and country. The chain is looked up through a
`PlaceHierarchy`, such as a `ContainmentSpine` built from a containment table or
a Who's On First meta CSV, joined on a gazetteer attribute of the resolved entry
(by default the Who's On First identifier). The result is recorded as a
`ContainmentChain` under the `HierarchyAnnotator.CONTAINMENT` layer on the
original mention span.

## Module

`org.apache.opennlp.addons:geo` provides the hierarchy, geocoding, and gazetteer
APIs. Core supplies the `Document` container and typed layers. Run the geocoding
stage before hierarchy annotation so resolved locations are available.
