<!--
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

# addons-eval

The evaluation suite of the OpenNLP add-ons. It mirrors core's `opennlp-eval-tests`
module: tests that call the production evaluators of each add-on on downloaded data,
skip with a clear message when the data is absent, and assert documented regression
floors when it is present. Every run writes one TSV per evaluation under
`target/eval-reports/` and prints a one-line summary per metric.

This module depends on the add-ons it evaluates (`subword`, `embeddings-core`,
`neural-postag`, `coref`, `coref-formats`), so it is the last add-on to land: it can only
build once those modules are in the reactor.

## What is in the module

- `EvalData` (main): locates the eval root and the downloaded models.
- `EvalReport` (main): one metric with its threshold and verdict, written as TSV.
- `EvalRuns` (test): the skip rules and the report step shared by the eval tests.
- Five eval tests, one per evaluation, listed below.

## Where the data lives

`EvalData` resolves two directories:

| Directory | Resolution order |
|---|---|
| models | `.opennlp` below the `OPENNLP_DOWNLOAD_HOME` system property, else the `OPENNLP_DOWNLOAD_HOME` environment variable, else the user's home. This is where core's `DownloadUtil` puts models, so one cache holds both. |
| eval root | `-Dopennlp.addons.eval.dir`, else the `OPENNLP_ADDONS_EVAL_DIR` environment variable, else `<models>/eval`. |

A blank setting counts as unset. With nothing set the layout is:

```
~/.opennlp/
  en-ner-person.bin  en-ner-location.bin  en-ner-organization.bin
  en-chunker.bin  en-pos-maxent.bin
  opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin  opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin
  eval/
    vector-search/
      model/              a StaticEmbeddingModel directory (vocab.txt, model.safetensors, ...)
      passages.jsonl      normalized passages, as the embeddings corpus tools write them
      dictionary.tsv      dictionary entries, headword and definition
    sentencepiece/
      <name>.model        a real SentencePiece model
      <name>.fixtures.tsv its reference encodings, from subword's gen_real_fixtures.py
    ud/
      train.conllu        a UD treebank's train split, renamed or linked
      test.conllu         its test split
    coref/
      ontogum/*.conllu    OntoGUM or CorefUD documents with Entity= annotations
      gap/gap-development.tsv
```

## The evaluations

| Test | Calls | Data | Models | Threshold |
|---|---|---|---|---|
| `VectorSearchEvalTest` | `SearchEvaluator.run` (embeddings-core), 4 bits, top 10 | `vector-search/` | none | fidelity recall@10 >= 0.80, rank-1 agreement >= 0.50, half-passage recall@10 >= 0.50 exact and >= 0.40 quantized, definition-to-headword MRR >= 0.02 |
| `SentencePieceParityEvalTest` | `SentencePieceTokenizer.encode` and `normalize` (subword) | `sentencepiece/` | none | exact parity (1.0) on pieces, ids, spans and normalized form; at least 30 fixtures per model |
| `BilstmPosEvalTest` | `BilstmPOSTrainer.train` with default settings, `BilstmPOSTagger` (neural-postag), core `POSEvaluator` over `ConlluPOSSampleStream` | `ud/` | none | UPOS word accuracy >= 0.85 |
| `OntoGumEvalTest` | `ConlluCorefDocumentStream` (coref-formats), `CorefAnnotator` and `CorefScorer` (coref), core `NameFinderAnnotator` and `ChunkerAnnotator` | `coref/ontogum/` | three `en-ner-*.bin`, `en-chunker.bin` | CoNLL average >= 0.30 |
| `GapEvalTest` | `CorefAnnotator` (coref) over core's sentence, token, POS, name finder and chunker annotators | `coref/gap/gap-development.tsv` | the two `opennlp-en-ud-ewt` models, `en-pos-maxent.bin`, three `en-ner-*.bin`, `en-chunker.bin` | F1 >= 0.30 |

The thresholds are regression floors below the figures the add-on chapters report
(OntoGUM test split CoNLL 46.8 with gold tags, GAP development F1 46.1 for the ranker). The
TSV report is the measurement; compare it across commits.

The data is downloaded by the person running the suite and never enters the repository.
Check each corpus's license before any use beyond measurement: GUM annotations are CC BY
4.0 and its texts carry their own licenses; GAP is Apache License 2.0; UD treebanks are
licensed per treebank.

## How to run

A plain build runs the module's unit tests and skips the eval tests:

```
mvn -pl addons-eval verify
```

With data in place, point the suite at it (or set `OPENNLP_ADDONS_EVAL_DIR`):

```
mvn -pl addons-eval verify -Dopennlp.addons.eval.dir=/path/to/eval
```

One evaluation at a time:

```
mvn -pl addons-eval test -Dtest=VectorSearchEvalTest -Dopennlp.addons.eval.dir=/path/to/eval
```

Reports land in `addons-eval/target/eval-reports/<evaluation>.tsv` with the columns
`metric`, `dataset`, `model`, `value`, `threshold`, `pass`.

## CI cache

The `maven.yml` workflow caches `~/.opennlp`, which holds the models `DownloadUtil`
fetches and the `eval` directory below it, keyed on the operating system and the hash of
`.github/models-manifest.txt`, the list of files the build may download. Editing that
manifest invalidates the cache; the workflow also exports `OPENNLP_DOWNLOAD_HOME` so
`DownloadUtil` and `EvalData` resolve the same directory.

## Follow-ups

Some evaluation logic lives only in the test trees of the add-ons and is not on this
module's compile path. This module uses production code where it exists and keeps the
remaining glue small; moving the following into main code would let it be shared:

- `embeddings-cli` test tree: `HnswFloatIndex` and `HnswBaseline`, the Lucene HNSW
  baseline run alongside `SearchEvaluator`. It is not run here because Lucene and the
  classes are test scoped; a small `embeddings-lucene` module would make the baseline a
  production evaluator.
- `subword` test tree: `SentencePieceFixtures`, the reader of the `.fixtures.tsv` parity
  format. `SentencePieceParityEvalTest` carries its own copy of that reader.
- `neural-postag` test tree: the word-based CoNLL-U sample stream in
  `ConlluPOSTaggerEvalTest`, which keeps syntactic words where core's `ConlluStream`
  merges multiword tokens. `BilstmPosEvalTest` uses core's stream, so its figure differs
  slightly on treebanks with contractions.
- `coref-formats` test tree: `CorefEvalSupport` (the three-model entity layer) and the GAP
  row reader of `GapCorefEvalTest`. Here the entity layer is core's `NameFinderAnnotator`
  over a composite of the three finders, and the GAP reader is a few lines in `GapEvalTest`.
