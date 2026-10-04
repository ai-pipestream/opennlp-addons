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
package opennlp.addons.eval.coref;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import opennlp.addons.eval.EvalRuns;
import opennlp.tools.chunker.ChunkerAnnotator;
import opennlp.tools.chunker.ChunkerME;
import opennlp.tools.chunker.ChunkerModel;
import opennlp.tools.coref.CorefMention;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.namefind.NameFinderAnnotator;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinder;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.util.Span;

/**
 * The downloaded models both coreference evaluations share, wrapped in core's document
 * annotators, and the mention partitions the scorer compares.
 */
final class CorefEvalModels {

  /** The entity models from the OpenNLP 1.5 model set, the types the resolver reads. */
  static final String[] NER_MODELS =
      {"en-ner-person.bin", "en-ner-location.bin", "en-ner-organization.bin"};

  /** The chunker that supplies noun-phrase mentions. */
  static final String CHUNKER_MODEL = "en-chunker.bin";

  /** A mention identity: the character span it covers. */
  record Mention(int start, int end) {
  }

  private CorefEvalModels() {
  }

  /**
   * Runs the three entity finders as one, keeping the first-found span wherever two overlap.
   */
  private static final class CompositeNameFinder implements TokenNameFinder {

    private final List<NameFinderME> finders;

    CompositeNameFinder(List<NameFinderME> finders) {
      this.finders = finders;
    }

    @Override
    public Span[] find(String[] tokens) {
      final List<Span> found = new ArrayList<>();
      for (final NameFinderME finder : finders) {
        for (final Span span : finder.find(tokens)) {
          if (found.stream().noneMatch(span::intersects)) {
            found.add(span);
          }
        }
      }
      found.sort((a, b) -> Integer.compare(a.getStart(), b.getStart()));
      return found.toArray(new Span[0]);
    }

    @Override
    public void clearAdaptiveData() {
      for (final NameFinderME finder : finders) {
        finder.clearAdaptiveData();
      }
    }
  }

  /**
   * Loads the entity annotator over the person, location and organization models.
   *
   * @return The annotator adding {@code Layers.ENTITIES}.
   * @throws IOException Thrown if a model cannot be read.
   */
  static DocumentAnnotator entityAnnotator() throws IOException {
    final List<NameFinderME> finders = new ArrayList<>();
    for (final String model : NER_MODELS) {
      finders.add(new NameFinderME(new TokenNameFinderModel(EvalRuns.DATA.model(model))));
    }
    return new NameFinderAnnotator(new CompositeNameFinder(finders));
  }

  /**
   * Loads the chunker annotator.
   *
   * @return The annotator adding {@link ChunkerAnnotator#CHUNKS}.
   * @throws IOException Thrown if the model cannot be read.
   */
  static DocumentAnnotator chunkerAnnotator() throws IOException {
    return new ChunkerAnnotator(
        new ChunkerME(new ChunkerModel(EvalRuns.DATA.model(CHUNKER_MODEL))));
  }

  /**
   * Groups a chains layer into entities of two or more mentions, dropping singletons as the
   * CoNLL-2012 scorer does. A span filed under two chains stays with the first chain so the
   * result is a partition.
   *
   * @param chains The gold or predicted chains layer.
   * @return The partition.
   */
  static List<Set<Mention>> partition(List<Annotation<CorefMention>> chains) {
    final Map<Integer, Set<Mention>> byChain = new TreeMap<>();
    final Set<Mention> seen = new HashSet<>();
    for (final Annotation<CorefMention> mention : chains) {
      final Mention identity = new Mention(mention.span().getStart(), mention.span().getEnd());
      if (seen.add(identity)) {
        byChain.computeIfAbsent(mention.value().chain(), key -> new HashSet<>())
            .add(identity);
      }
    }
    final List<Set<Mention>> partition = new ArrayList<>();
    for (final Set<Mention> entity : byChain.values()) {
      if (entity.size() > 1) {
        partition.add(entity);
      }
    }
    return partition;
  }
}
