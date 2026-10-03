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


# url-normalizer

Optional OpenNLP components, published as `org.apache.opennlp.addons:url-normalizer`.

`BoundedUrlCharSequenceNormalizer` removes each `http` or `https` URL as a whole, the way it
is bounded in running text, and each email address. Core's `UrlCharSequenceNormalizer` stops
at the first character outside its ASCII body set, which the existing language detector
models were trained on, so this one is an opt-in for new training pipelines.

Build and run the module tests from the repository root:

```sh
mvn -pl url-normalizer -am verify -Dopennlp.forkCount=1
```

## Source

Migrated from [ai-pipestream/opennlp OPENNLP-1946-url-normalizer-strict](https://github.com/ai-pipestream/opennlp/tree/5a5fd37343c538e9f2f0982c96b443f91750300c).
The classes moved from core's `opennlp.tools.util.normalizer` to `opennlp.tools.normalizer.url`.
Core's `UrlCharSequenceNormalizer` is unchanged; the add-on keeps its own copy of the email scan.
