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


# native-image

Optional OpenNLP components, published as `org.apache.opennlp.addons:native-image`.

Add this jar next to the OpenNLP modules an application uses, and the GraalVM image builder
picks up the reachability metadata for the resources the core jars read by a computed name
(stopword lists, UAX 29 and normalizer tables, default feature descriptors, the version file).
`NativeSmoke` is a minimal native OpenNLP application and the smoke test for the image.

This add-on needs the core extension registry and native image guards (OPENNLP-1954), which
are not in a released core yet. Until then build it against a local core install that has
them:

```sh
mvn -pl native-image -am verify -Dopennlp.version=<that core version>
```

Build and run the image (GraalVM JDK 25 or later, models as named in `NativeSmoke`):

```sh
mvn -Pnative -pl native-image -am verify -Dopennlp.native.models=/path/to/models
```

## Source

Migrated from [ai-pipestream/opennlp OPENNLP-1954-native-prep](https://github.com/ai-pipestream/opennlp/tree/49152b6eb),
commit bb71fa043: the `opennlp-native-tests` module, the reachability metadata of
opennlp-runtime and opennlp-formats (merged into one file here, since the image builder reads
resource globs from any jar), its coverage test, the CI job and the manual chapter. The
registry, ModelLoader and native image guards stay in core.
