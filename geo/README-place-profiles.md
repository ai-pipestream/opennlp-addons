<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Numeric place profiles

`PlaceProfiles` compares places by a user-supplied table of numeric
measurements, such as population density, income or elevation, instead of by
their names. Each column is standardized to mean zero and unit variance at load,
and places are compared and ranked by the cosine similarity of their
standardized profiles. Place identifiers and metric names must be unique; the
choice of metrics and places determines the scores. Categorical data is not
encoded.

## Module

`org.apache.opennlp.addons:geo` provides the class. No measurement data is
bundled and no dependency is added; users are responsible for the licenses of
their sources.
