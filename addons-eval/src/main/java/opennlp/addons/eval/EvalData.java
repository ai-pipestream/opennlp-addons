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
package opennlp.addons.eval;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

import opennlp.tools.util.StringUtil;

/**
 * Locates the evaluation data and the downloaded models of the add-on eval suite.
 *
 * <p>The model directory follows core's {@code DownloadUtil}: the {@value #DOWNLOAD_DIRECTORY}
 * directory below the {@value #DOWNLOAD_HOME} system property, else the environment variable of
 * the same name, else the user's home. The eval root is the {@value #DIRECTORY_PROPERTY} system
 * property, else the {@value #DIRECTORY_VARIABLE} environment variable, else the
 * {@value #EVAL_DIRECTORY} directory below the model directory. A blank setting counts as
 * unset. Each evaluation keeps its files in one subdirectory of the root, named by
 * {@link #dataset(String)}; the module README lists them.</p>
 *
 * <p>The locator only computes paths. The eval tests check that a path exists and skip with
 * a message naming it when it does not.</p>
 *
 * @since 3.0.0
 */
public final class EvalData {

  /** The system property naming the eval root. */
  public static final String DIRECTORY_PROPERTY = "opennlp.addons.eval.dir";

  /** The environment variable naming the eval root when the property is unset. */
  public static final String DIRECTORY_VARIABLE = "OPENNLP_ADDONS_EVAL_DIR";

  /**
   * The system property, and environment variable, naming the parent of the download
   * directory, as core's {@code DownloadUtil} reads it.
   */
  public static final String DOWNLOAD_HOME = "OPENNLP_DOWNLOAD_HOME";

  /** The download directory below the download home. */
  public static final String DOWNLOAD_DIRECTORY = ".opennlp";

  /** The default eval root below the download directory. */
  public static final String EVAL_DIRECTORY = "eval";

  private static final String USER_HOME = "user.home";

  private final Path root;
  private final Path models;

  /**
   * Initializes the locator with explicit directories.
   *
   * @param root The eval root. Must not be {@code null}.
   * @param models The directory holding the downloaded models. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}.
   */
  public EvalData(Path root, Path models) {
    if (root == null) {
      throw new IllegalArgumentException("root must not be null");
    }
    if (models == null) {
      throw new IllegalArgumentException("models must not be null");
    }
    this.root = root;
    this.models = models;
  }

  /**
   * Resolves the directories from the system properties and the environment of this JVM.
   *
   * @return The locator. Never {@code null}.
   * @throws IllegalStateException Thrown if the {@code user.home} property is unset.
   */
  public static EvalData fromEnvironment() {
    return resolve(System::getProperty, System::getenv);
  }

  /**
   * Resolves the directories from the given settings, in the order the class comment lists.
   *
   * @param properties Looks up a system property by name; {@code null} for an unset one.
   *                   Must not be {@code null}.
   * @param environment Looks up an environment variable by name; {@code null} for an unset
   *                    one. Must not be {@code null}.
   * @return The locator. Never {@code null}.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}.
   * @throws IllegalStateException Thrown if {@code properties} has no {@code user.home}.
   */
  public static EvalData resolve(UnaryOperator<String> properties,
      UnaryOperator<String> environment) {
    if (properties == null) {
      throw new IllegalArgumentException("properties must not be null");
    }
    if (environment == null) {
      throw new IllegalArgumentException("environment must not be null");
    }
    String downloadHome = setting(properties.apply(DOWNLOAD_HOME));
    if (downloadHome == null) {
      downloadHome = setting(environment.apply(DOWNLOAD_HOME));
    }
    if (downloadHome == null) {
      downloadHome = setting(properties.apply(USER_HOME));
    }
    if (downloadHome == null) {
      throw new IllegalStateException("the user.home system property is unset");
    }
    final Path models = Path.of(downloadHome).resolve(DOWNLOAD_DIRECTORY);

    String root = setting(properties.apply(DIRECTORY_PROPERTY));
    if (root == null) {
      root = setting(environment.apply(DIRECTORY_VARIABLE));
    }
    return new EvalData(root == null ? models.resolve(EVAL_DIRECTORY) : Path.of(root), models);
  }

  /**
   * Treats a blank setting as unset.
   *
   * @param value The raw setting.
   * @return The setting, or {@code null} if it is unset or blank.
   */
  private static String setting(String value) {
    return StringUtil.isUnicodeBlank(value) ? null : value;
  }

  /** {@return the eval root; every dataset is a subdirectory of it} */
  public Path root() {
    return root;
  }

  /** {@return the directory holding the downloaded models} */
  public Path models() {
    return models;
  }

  /**
   * Names the directory of one evaluation's data.
   *
   * @param name The dataset directory name. Must be a plain name: not blank, not {@code .} or
   *             {@code ..}, and without a path separator.
   * @return The dataset directory below {@link #root()}. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code name} is {@code null} or not a plain name.
   */
  public Path dataset(String name) {
    return root.resolve(plainName(name, "dataset"));
  }

  /**
   * Names one downloaded model file.
   *
   * @param fileName The model file name, under the same constraints as
   *                 {@link #dataset(String)}.
   * @return The model file below {@link #models()}. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code fileName} is {@code null} or not a
   *                                  plain name.
   */
  public Path model(String fileName) {
    return models.resolve(plainName(fileName, "fileName"));
  }

  /**
   * Checks that a name is one path element.
   *
   * @param name The name to check.
   * @param what The parameter name for the error message.
   * @return The checked name.
   * @throws IllegalArgumentException Thrown if the name is not a plain name.
   */
  private static String plainName(String name, String what) {
    if (name == null) {
      throw new IllegalArgumentException(what + " must not be null");
    }
    if (StringUtil.isUnicodeBlank(name)) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
    if (".".equals(name) || "..".equals(name)) {
      throw new IllegalArgumentException(what + " must not be a relative directory: " + name);
    }
    if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
      throw new IllegalArgumentException(what + " must not contain a path separator: " + name);
    }
    return name;
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return "EvalData[root=" + root + ", models=" + models + "]";
  }
}
