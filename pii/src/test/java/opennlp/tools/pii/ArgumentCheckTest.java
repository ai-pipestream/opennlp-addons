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

package opennlp.tools.pii;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.document.Document;
import opennlp.tools.document.LayerKey;
import opennlp.tools.util.Span;

/**
 * Checks the exception type and message of the argument checks at the public entry
 * points, which name the rejected parameter the way {@code ParamChecks} does.
 */
class ArgumentCheckTest {

  private static final HmacTokenizer TOKENIZER = new HmacTokenizer(
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
  private static final PiiExtractor EXTRACTOR = new CursorPiiExtractor();
  private static final Document DOCUMENT = Document.of("text");

  /**
   * Provides one rejected call per argument check.
   *
   * @return The call, as a description, the executable and the expected message.
   */
  private static Stream<Arguments> rejectedCalls() {
    return Stream.of(
        call("CursorPiiExtractor(null)", () -> new CursorPiiExtractor(null),
            "types must not be null or empty"),
        call("CursorPiiExtractor(empty)", () -> new CursorPiiExtractor(Set.of()),
            "types must not be null or empty"),
        call("CursorPiiExtractor(unknown)", () -> new CursorPiiExtractor(Set.of("ssn")),
            "types contains an unrecognized type: ssn"),
        call("NetworkPiiExtractor(empty)", () -> new NetworkPiiExtractor(Set.of()),
            "types must not be null or empty"),
        call("extract(null)", () -> EXTRACTOR.extract(null), "text must not be null"),
        call("BankingPiiExtractor.extract(null)", () -> new BankingPiiExtractor().extract(null),
            "text must not be null"),
        call("CompositePiiExtractor()", () -> new CompositePiiExtractor(),
            "extractors must not be null or empty"),
        call("CompositePiiExtractor(null list)",
            () -> new CompositePiiExtractor((List<PiiExtractor>) null),
            "extractors must not be null or empty"),
        call("PiiAnnotator(null)", () -> new PiiAnnotator(null), "extractor must not be null"),
        call("annotate(null)", () -> new PiiAnnotator(EXTRACTOR).annotate(null),
            "document must not be null"),
        call("PiiMention(null span)", () -> new PiiMention(null, "t", "n"),
            "span must not be null"),
        call("PiiMention(blank type)", () -> new PiiMention(new Span(0, 1), " ", "n"),
            "type must not be null or blank"),
        call("PiiMention(null normalized)", () -> new PiiMention(new Span(0, 1), "t", null),
            "normalized must not be null or blank"),
        call("PiiTypePriority.rank(null)", () -> PiiTypePriority.rank(null),
            "type must not be null"),
        call("MaskPolicies.forType(null)", () -> MaskPolicies.forType(null),
            "type must not be null"),
        call("keepingTrailing(-1)", () -> MaskPolicy.of('*').keepingTrailing(-1),
            "count must not be negative"),
        call("Masker.mask(null document)", () -> Masker.mask(null, PiiAnnotator.PII, '*'),
            "document must not be null"),
        call("Masker.mask(null layer)",
            () -> Masker.mask(DOCUMENT, (LayerKey<?>) null, '*'), "layer must not be null"),
        call("Masker.mask(null layers)",
            () -> Masker.mask(DOCUMENT, (Collection<LayerKey<?>>) null, '*'),
            "layers must not be null or empty"),
        call("Masker.mask(null policy)",
            () -> Masker.mask(DOCUMENT, List.of(PiiAnnotator.PII), (MaskPolicy) null),
            "policy must not be null"),
        call("Masker.mask(null policies)",
            () -> Masker.mask(DOCUMENT, PiiAnnotator.PII, (Function<PiiMention, MaskPolicy>) null),
            "policies must not be null"),
        call("Pseudonymizer(null prefix)", () -> new Pseudonymizer(null, ""),
            "prefix must not be null"),
        call("Pseudonymizer(null suffix)", () -> new Pseudonymizer("", null),
            "suffix must not be null"),
        call("rewrite(null text)", () -> new Pseudonymizer().rewrite(null, List.of()),
            "text must not be null"),
        call("rewrite(null mentions)", () -> TOKENIZER.rewrite("x", null),
            "mentions must not be null"),
        call("rewrite(null document)", () -> TOKENIZER.rewrite((Document) null),
            "document must not be null"),
        call("HmacTokenizer(null)", () -> new HmacTokenizer(null), "key must not be null"),
        call("token(null mention)", () -> TOKENIZER.token((PiiMention) null),
            "mention must not be null"),
        call("token(blank type)", () -> TOKENIZER.token(" ", "v"),
            "type must not be null or blank"),
        call("token(empty value)", () -> TOKENIZER.token("t", ""),
            "value must not be null or empty"),
        call("PiiAuditReport.of(null mentions)",
            () -> PiiAuditReport.of((List<PiiMention>) null, TOKENIZER),
            "mentions must not be null"),
        call("PiiAuditReport.of(null tokenizer)", () -> PiiAuditReport.of(List.of(), null),
            "tokenizer must not be null"),
        call("PiiAuditReport.of(-1 samples)",
            () -> PiiAuditReport.of(List.of(), TOKENIZER, -1),
            "samplesPerType must not be negative"),
        call("samples(null)", () -> PiiAuditReport.of(List.of(), TOKENIZER).samples(null),
            "type must not be null"),
        call("remap(null)",
            () -> new Pseudonymizer().rewrite("x", List.of()).remap(null),
            "annotations must not be null"),
        call("mapSpan(null)",
            () -> new Pseudonymizer().rewrite("x", List.of()).mapSpan(null),
            "span must not be null"));
  }

  private static Arguments call(String description, Executable executable, String message) {
    return Arguments.of(description, executable, message);
  }

  /**
   * Checks one rejected call.
   *
   * @param description The call.
   * @param executable The call to run.
   * @param message The expected message.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedCalls")
  void testRejectsWithNamedParameter(String description, Executable executable,
      String message) {
    final IllegalArgumentException failure =
        Assertions.assertThrows(IllegalArgumentException.class, executable, description);

    Assertions.assertEquals(message, failure.getMessage(), description);
  }
}
