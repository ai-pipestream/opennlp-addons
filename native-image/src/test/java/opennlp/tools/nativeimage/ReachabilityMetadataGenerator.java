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

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import opennlp.tools.commons.Trainer;
import opennlp.tools.doccat.FeatureGenerator;
import opennlp.tools.entitylinker.EntityLinker;
import opennlp.tools.ml.maxent.GISTrainer;
import opennlp.tools.ml.model.AbstractModel;
import opennlp.tools.ml.model.AbstractModelReader;
import opennlp.tools.ml.model.AbstractModelWriter;
import opennlp.tools.ml.model.DataIndexer;
import opennlp.tools.ml.model.DataReader;
import opennlp.tools.ml.model.SequenceClassificationModel;
import opennlp.tools.ml.naivebayes.NaiveBayesTrainer;
import opennlp.tools.ml.perceptron.PerceptronTrainer;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.util.BaseToolFactory;
import opennlp.tools.util.SequenceCodec;
import opennlp.tools.util.featuregen.GeneratorFactory;
import opennlp.tools.util.jvm.StringInterner;
import opennlp.tools.util.model.ArtifactSerializer;
import opennlp.tools.util.model.BaseModel;

/**
 * Builds the reachability metadata this add-on ships, from the OpenNLP jars on the class path.
 * Core creates classes by the name a model, a feature descriptor or the training parameters
 * give, so every class it can create that way needs its constructor registered for reflection.
 * Run {@link #main(String[])} to rewrite the file after a core upgrade; the test fails until
 * the shipped file matches.
 */
public final class ReachabilityMetadataGenerator {

  static final String METADATA =
      "META-INF/native-image/org.apache.opennlp.addons/native-image/reachability-metadata.json";

  /** Resources core reads by a name computed at run time. */
  static final List<String> RESOURCE_GLOBS = List.of(
      "opennlp/tools/namefind/ner-default-features.xml",
      "opennlp/tools/postag/pos-default-features.xml",
      "opennlp/tools/stopword/*.txt",
      "opennlp/tools/tokenize/uax29/*.txt",
      "opennlp/tools/util/normalizer/*.txt",
      "opennlp/tools/util/opennlp.version");

  /** Types ExtensionLoader creates through their no-argument constructor. */
  private static final List<Class<?>> NO_ARG_TYPES = List.of(
      BaseToolFactory.class, ArtifactSerializer.class, SequenceCodec.class,
      FeatureGenerator.class, EntityLinker.class, Trainer.class, Tokenizer.class,
      GeneratorFactory.AbstractXmlFeatureGeneratorFactory.class, StringInterner.class,
      DataIndexer.class);

  /** Constructors model loading and the model resolver look up by their parameter types. */
  private static final Map<Class<?>, List<Class<?>[]>> TYPED_CONSTRUCTORS = Map.of(
      AbstractModelReader.class, List.<Class<?>[]>of(new Class<?>[] {DataReader.class}),
      AbstractModelWriter.class, List.<Class<?>[]>of(
          new Class<?>[] {AbstractModel.class, DataOutputStream.class}),
      BaseModel.class, List.<Class<?>[]>of(new Class<?>[] {InputStream.class},
          new Class<?>[] {Path.class}));

  /** Jars to scan, each named by a class it contains. */
  private static final List<Class<?>> JARS = List.of(
      BaseToolFactory.class, SequenceClassificationModel.class, AbstractModel.class,
      GISTrainer.class, PerceptronTrainer.class, NaiveBayesTrainer.class);

  private static final String CLASS_SUFFIX = ".class";
  private static final String SNOWBALL_PROGRAM = "opennlp.tools.stemmer.snowball.SnowballProgram";
  private static final String COMPUTE_TASK =
      "opennlp.tools.ml.maxent.quasinewton.ParallelNegLogLikelihood$ComputeTask";

  private ReachabilityMetadataGenerator() {
  }

  public static void main(String[] args) throws IOException, URISyntaxException {
    final Path target = Path.of(args.length > 0 ? args[0] : "src/main/resources").resolve(METADATA);
    Files.writeString(target, generate(), StandardCharsets.UTF_8);
    System.out.println("wrote " + target.toAbsolutePath());
  }

  /** {@return the metadata JSON for the OpenNLP jars on the class path} */
  static String generate() throws IOException, URISyntaxException {
    final Map<String, List<String>> constructors = new TreeMap<>();
    final List<String> methodTypes = new ArrayList<>();
    for (String name : classNames()) {
      final Class<?> clazz;
      try {
        clazz = Class.forName(name, false, ReachabilityMetadataGenerator.class.getClassLoader());
      } catch (ClassNotFoundException | LinkageError e) {
        continue;
      }
      if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
        continue;
      }
      final List<String> found = new ArrayList<>();
      for (Class<?> type : NO_ARG_TYPES) {
        if (type.isAssignableFrom(clazz)) {
          addConstructor(clazz, found, new Class<?>[0]);
        }
      }
      for (Map.Entry<Class<?>, List<Class<?>[]>> e : TYPED_CONSTRUCTORS.entrySet()) {
        if (e.getKey().isAssignableFrom(clazz)) {
          for (Class<?>[] parameters : e.getValue()) {
            addConstructor(clazz, found, parameters);
          }
        }
      }
      if (clazz.getSuperclass() != null && COMPUTE_TASK.equals(clazz.getSuperclass().getName())) {
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
          addConstructor(clazz, found, c.getParameterTypes());
        }
      }
      if (!found.isEmpty()) {
        constructors.put(name, found.stream().distinct().toList());
      }
      if (extendsSnowballProgram(clazz)) {
        methodTypes.add(name);
      }
    }
    return toJson(constructors, methodTypes);
  }

  /** Snowball stemmers look up their own routines through method handles. */
  private static boolean extendsSnowballProgram(Class<?> clazz) {
    for (Class<?> c = clazz.getSuperclass(); c != null; c = c.getSuperclass()) {
      if (SNOWBALL_PROGRAM.equals(c.getName())) {
        return true;
      }
    }
    return false;
  }

  private static void addConstructor(Class<?> clazz, List<String> found, Class<?>[] parameters) {
    try {
      clazz.getDeclaredConstructor(parameters);
    } catch (NoSuchMethodException e) {
      return;
    }
    found.add(String.join(",", Arrays.stream(parameters).map(Class::getTypeName).toList()));
  }

  private static List<String> classNames() throws IOException, URISyntaxException {
    final List<String> names = new ArrayList<>();
    for (Class<?> anchor : JARS) {
      final Path location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (Files.isDirectory(location)) {
        collect(location, names);
      } else {
        try (FileSystem jar = FileSystems.newFileSystem(location, Map.of())) {
          collect(jar.getPath("/"), names);
        }
      }
    }
    return names.stream().distinct().sorted().toList();
  }

  private static void collect(Path root, List<String> names) throws IOException {
    final Path opennlp = root.resolve("opennlp");
    if (!Files.isDirectory(opennlp)) {
      return;
    }
    try (Stream<Path> files = Files.walk(opennlp)) {
      files.map(p -> root.relativize(p).toString())
          .filter(n -> n.endsWith(CLASS_SUFFIX) && !n.endsWith("module-info.class"))
          .map(n -> n.substring(0, n.length() - CLASS_SUFFIX.length()).replace('/', '.'))
          .forEach(names::add);
    }
  }

  private static String toJson(Map<String, List<String>> constructors, List<String> methodTypes) {
    final StringBuilder json = new StringBuilder();
    json.append("{\n  \"comment\": \"GraalVM native image metadata for the OpenNLP core jars, ")
        .append("generated by ReachabilityMetadataGenerator.\",\n  \"reflection\": [\n");
    final List<String> entries = new ArrayList<>();
    final Map<String, String> sorted = new TreeMap<>();
    constructors.forEach((type, list) -> {
      final List<String> methods = new ArrayList<>();
      for (String parameters : list) {
        final String types = parameters.isEmpty() ? ""
            : "\"" + String.join("\", \"", parameters.split(",")) + "\"";
        methods.add("{\"name\": \"<init>\", \"parameterTypes\": [" + types + "]}");
      }
      sorted.put(type, "    {\"type\": \"" + type + "\", \"methods\": [" + String.join(", ", methods) + "]}");
    });
    for (String type : methodTypes) {
      sorted.merge(type, "    {\"type\": \"" + type + "\", \"allDeclaredMethods\": true}",
          (a, b) -> a.substring(0, a.length() - 1) + ", \"allDeclaredMethods\": true}");
    }
    entries.addAll(sorted.values());
    json.append(String.join(",\n", entries)).append("\n  ],\n  \"resources\": [\n");
    json.append(String.join(",\n", RESOURCE_GLOBS.stream()
        .map(g -> "    {\"glob\": \"" + g + "\"}").toList()));
    return json.append("\n  ]\n}\n").toString();
  }
}
