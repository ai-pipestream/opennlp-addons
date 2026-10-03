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


# parser-tagger

Optional OpenNLP components, published as `org.apache.opennlp.addons:parser-tagger`.

`CustomTaggerParsers` creates the core chunking or tree insert parser, picked from the model,
with your own `POSTagger`, and optionally your own `Chunker`, in place of the ones the
`ParserModel` carries. It needs no core change: the parsers are subclassed and the protected
tagger and chunker fields are replaced in the constructor.

Build and run the module tests from the repository root:

```sh
mvn -pl parser-tagger -am verify -Dopennlp.forkCount=1
```

## Source

Replaces the core change on [ai-pipestream/opennlp OPENNLP-XXXX-parser-tagger-injection](https://github.com/ai-pipestream/opennlp/tree/df46cda76bb899e35158a32eb82e29bbaba5fc02),
which added public tagger constructors to both parsers.
`src/test/resources/opennlp/tools/parser/custom/en_head_rules` is a copy of core's test head rules.
