<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# noise

Optional OpenNLP components, published as `org.apache.opennlp.addons:noise`.

Build and run the module tests from the repository root:

```sh
mvn -pl noise -am verify -Dopennlp.forkCount=1
```

The module builds against core `3.0.0-SNAPSHOT`. Use exact versions when consuming
snapshots; Maven version ranges may select branch snapshots ahead of releases.

## Contents

`StructuralNoiseScorer` scores whitespace-delimited ASCII tokens for OCR damage,
gibberish, repeated letters and base64-shaped runs, with an optional dictionary
predicate for misspelled words. `NoiseAnnotator` writes the `opennlp:noise` layer
and, in its default mode, excludes the spans of the `opennlp:assets` layer written
by the embedded-assets module. The manual chapter "Noise Scoring" describes the
severities and the rules.
