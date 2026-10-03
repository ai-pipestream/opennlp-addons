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
picks up its reachability metadata: the constructors core creates by class name (tool
factories, serializers, feature generator factories, sequence codecs, trainers, model readers
and writers, model classes), the Snowball routines found through method handles, and the
resources core reads by a computed name (stopword lists, UAX 29 and normalizer tables,
default feature descriptors, the version file). No core change is needed; it works with the
released 3.0.0-M6.

`NativeSmoke` is a minimal native OpenNLP application and the smoke test for the image.

Build and run the module tests from the repository root:

```sh
mvn -pl native-image -am verify -Dopennlp.forkCount=1
```

Build and run the image (GraalVM JDK 25 or later, models as named in `NativeSmoke`):

```sh
mvn -Pnative -pl native-image -am verify -Dopennlp.native.models=/path/to/models
```

After a core upgrade, `ReachabilityMetadataTest` fails until the metadata is regenerated:

```sh
mvn -pl native-image test-compile
java -cp "$(mvn -q -pl native-image dependency:build-classpath -Dmdep.outputFile=/dev/stdout):native-image/target/test-classes" \
    opennlp.tools.nativeimage.ReachabilityMetadataGenerator native-image/src/main/resources
```

## Source

Started from [ai-pipestream/opennlp OPENNLP-1954-native-prep](https://github.com/ai-pipestream/opennlp/tree/49152b6eb):
the smoke application, the resource metadata, the CI job and the manual chapter. That branch
removed reflection from core with an extension registry; this add-on registers the reflective
constructors in GraalVM metadata instead, so core stays as it is.
