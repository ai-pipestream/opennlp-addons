/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package opennlp.tools.nativeimage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.stopword.StopwordLists;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the reachability metadata of this add-on in step with the resources the
 * opennlp-runtime jar bundles: every non-class file under {@code opennlp/} in that jar must
 * be covered by a resource glob, or a native image cannot read it.
 */
public class ReachabilityMetadataTest {

  private static final String METADATA = ReachabilityMetadataGenerator.METADATA;

  private static final String GLOB_KEY = "\"glob\"";

  private static final String CLASS_SUFFIX = ".class";

  private static List<PathMatcher> globs;

  @BeforeAll
  static void readGlobs() throws IOException {
    final String json;
    try (InputStream in = ReachabilityMetadataTest.class.getClassLoader()
        .getResourceAsStream(METADATA)) {
      assertNotNull(in, "expected " + METADATA + " on the class path");
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    globs = new ArrayList<>();
    int at = json.indexOf(GLOB_KEY);
    while (at >= 0) {
      final int open = json.indexOf('"', at + GLOB_KEY.length());
      final int close = json.indexOf('"', open + 1);
      globs.add(FileSystems.getDefault().getPathMatcher("glob:" + json.substring(open + 1, close)));
      at = json.indexOf(GLOB_KEY, close);
    }
    assertFalse(globs.isEmpty(), "no resource globs in " + METADATA);
  }

  static Stream<String> runtimeResources() throws IOException, URISyntaxException {
    final Path location = Path.of(StopwordLists.class.getProtectionDomain()
        .getCodeSource().getLocation().toURI());
    if (Files.isDirectory(location)) {
      return resourcesUnder(location);
    }
    try (FileSystem jar = FileSystems.newFileSystem(location, Map.of())) {
      return resourcesUnder(jar.getPath("/"));
    }
  }

  private static Stream<String> resourcesUnder(Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root.resolve("opennlp"))) {
      return files.filter(Files::isRegularFile)
          .map(p -> root.relativize(p).toString())
          .filter(name -> !name.endsWith(CLASS_SUFFIX))
          .toList().stream();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("runtimeResources")
  void testResourceIsCoveredByMetadata(String resource) {
    final Path path = Path.of(resource);
    assertTrue(globs.stream().anyMatch(g -> g.matches(path)),
        resource + " is not covered by a resource glob in " + METADATA);
  }

  @Test
  void testShippedMetadataMatchesTheCoreJars() throws IOException, URISyntaxException {
    final String shipped;
    try (InputStream in = ReachabilityMetadataTest.class.getClassLoader()
        .getResourceAsStream(METADATA)) {
      assertNotNull(in, "expected " + METADATA + " on the class path");
      shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertEquals(ReachabilityMetadataGenerator.generate(), shipped,
        "the metadata is out of date; run ReachabilityMetadataGenerator.main");
  }

  @Test
  void testMetadataDoesNotClaimClasses() {
    final Path classFile = Path.of("opennlp", "tools", "util", "Version.class");
    assertFalse(globs.stream().anyMatch(g -> g.matches(classFile)),
        "resource globs must not match class files");
  }
}
