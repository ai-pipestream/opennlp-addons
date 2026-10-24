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

# dehyphenation

Optional OpenNLP components, published as `org.apache.opennlp.addons:dehyphenation`.

Build and run the module tests from the repository root:

```sh
mvn -pl dehyphenation -am verify -Dopennlp.forkCount=1
```

The module builds against core `3.0.0-SNAPSHOT`. Use exact versions when consuming
snapshots; Maven version ranges may select branch snapshots ahead of releases.

## Contents

`DehyphenationCharSequenceNormalizer` joins a word that a hyphen and a line break split,
such as `litiga-` and `tion`, and maps the joined word back to the original text.
`RetokenizingTermVectorAnnotator` tokenizes the normalized text and writes the
`opennlp:term-vectors` layer, so a joined word becomes one term. The manual chapter
"De-hyphenation" lists the hyphens, line breaks and the cases that keep the hyphen.
