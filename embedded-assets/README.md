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

# embedded-assets

Optional OpenNLP components, published as `org.apache.opennlp.addons:embedded-assets`.

Build and run the module tests from the repository root:

```sh
mvn -pl embedded-assets -am verify -Dopennlp.forkCount=1
```

The module builds against core `3.0.0-SNAPSHOT`. Use exact versions when consuming
snapshots; Maven version ranges may select branch snapshots ahead of releases.

## Contents

`CursorAssetDetector` finds base64 content in `data:` URIs, bare runs with a
recognized file signature, RFC 7468 PEM envelopes and compact JSON Web Tokens, and
reports each as an `EmbeddedAsset` with exact spans over the original text.
`AssetAnnotator` writes the `opennlp:assets` layer and `AssetFolder` replaces the
assets with text while keeping an alignment to the original offsets. The manual
chapter "Embedded Asset Detection" describes the recognized formats and their limits.
