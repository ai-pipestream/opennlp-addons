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
package opennlp.embeddings.spi;

import opennlp.tools.util.ext.Provider;

/**
 * Creates independently owned {@link TeacherEncoder teacher encoders}. Providers register
 * through {@link java.util.ServiceLoader} under this interface's name and are resolved with
 * {@link opennlp.tools.util.ext.Providers Providers.of(TeacherEncoderProvider.class)}: a
 * provider takes part in selection when it {@link #isAvailable() is available} and
 * {@link #supports(opennlp.tools.util.ext.ProviderSpec) supports} the spec naming the teacher's
 * model file, and the highest {@link #priority()} wins. The configuration key
 * {@code opennlp.provider.opennlp.embeddings.spi.TeacherEncoderProvider} pins a provider by
 * name. Constructors must not load models or initialize native runtimes.
 * Thread safety is implementation specific.
 *
 * @since 3.0.0
 */
public interface TeacherEncoderProvider extends Provider<TeacherEncoder> {
}
