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

/**
 * The add-on evaluation suite: {@link opennlp.addons.eval.EvalData} locates the downloaded
 * data and models, {@link opennlp.addons.eval.EvalReport} records each measured metric.
 * The evaluations themselves are tests in this module that call the production evaluators
 * of the add-ons and skip when their data is absent.
 */
package opennlp.addons.eval;
