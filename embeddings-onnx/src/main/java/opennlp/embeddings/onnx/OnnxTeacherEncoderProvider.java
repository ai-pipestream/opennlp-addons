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
package opennlp.embeddings.onnx;

import java.io.IOException;

import ai.onnxruntime.OrtEnvironment;

import opennlp.embeddings.spi.TeacherEncoder;
import opennlp.embeddings.spi.TeacherEncoderProvider;
import opennlp.tools.util.ParamChecks;
import opennlp.tools.util.ext.ProviderSpec;

/**
 * Provides ONNX teacher encoders under the name {@value #NAME}. It supports a spec whose location
 * is a local file named {@code *.onnx}, in any letter case, and that has no options. The ONNX
 * Runtime is initialized by {@link #create(ProviderSpec)}, not by construction or by the checks.
 *
 * @since 3.0.0
 */
public final class OnnxTeacherEncoderProvider implements TeacherEncoderProvider {

  /** The name of this provider. */
  public static final String NAME = "onnx";

  private static final String MODEL_SUFFIX = ".onnx";

  /** Creates a provider without opening a model. */
  public OnnxTeacherEncoderProvider() {
  }

  /** {@inheritDoc} */
  @Override
  public String name() {
    return NAME;
  }

  /**
   * {@inheritDoc}
   * Checks that the ONNX Runtime classes are present without initializing the runtime.
   */
  @Override
  public boolean isAvailable() {
    try {
      return OrtEnvironment.class.getName() != null;
    } catch (LinkageError e) {
      return false;
    }
  }

  /** {@inheritDoc} */
  @Override
  public boolean supports(ProviderSpec spec) {
    ParamChecks.requireNonNullArg(spec, "spec");
    return spec.path().isPresent() && spec.locationEndsWith(MODEL_SUFFIX) && spec.hasOnlyOptions();
  }

  /** {@inheritDoc} */
  @Override
  public TeacherEncoder create(ProviderSpec spec) throws IOException {
    if (spec == null || !supports(spec)) {
      throw new IllegalArgumentException("spec is not supported: " + spec);
    }
    return OnnxTeacherEncoder.load(spec.path().orElseThrow());
  }
}
