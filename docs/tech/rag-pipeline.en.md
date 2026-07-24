# The RAG pipeline — why each step exists

> 🌍 Version française : **[rag-pipeline.fr.md](rag-pipeline.fr.md)**.

> **What this document is** — the **design reasoning** (the "why" of each step).
> Implementation facts (defaults, limits) are authoritative in
> [technical-doc.fr.md](technical-doc.fr.md); the big picture is in
> [architecture.en.md](../architecture.en.md).

The quality of Spectra's answers doesn't come from the model alone: it comes from **what we
give it to read**. The retrieval pipeline is therefore the heart of the product. This document
explains the **reasoning** behind each step — the *why* before the *how* — then points to the
class that implements it and its real parameters.

Every module below can be **enabled independently** through configuration. A minimal deployment
uses only vector retrieval + generation; you add the steps one at a time depending on the corpus
and the latency budget. The conductor is
[`RagService`](../../backend/src/main/java/fr/spectra/service/RagService.java).

## Overview

Execution order (optional modules are in brackets):

1. **[Adaptive RAG]** classifies the query → direct answer · standard · agentic
2. **[Conversational RAG]** rewrites the question using the conversation history
3. **[Long-Context Bypass]** if the corpus is small, load everything — *without* retrieval → §6
4. **[Multi-Query]** generates *N* reformulations, one retrieval per variant, fusion → §2
5. **Retrieval**: pure vector, or **[Hybrid Search]** BM25 + vectors fused by **RRF** → §3
6. **[Re-ranking]** a Cross-Encoder re-scores the short list → §4
7. **[Semantic Dedup]** removes near-identical chunks (Jaccard)
8. **[Corrective RAG]** drops chunks judged irrelevant (LLM grading)
9. **[Context Compression]** extracts the useful passages inside each chunk → §5
10. **[Agentic RAG]** a ReAct reasoning loop when the strategy is *agentic*
11. **[Self-RAG]** generates, then self-evaluates and refines if needed
12. **Generation** of the answer by the LLM

Upstream of all this, at **ingestion** time, the text is split into chunks → §1.

| Step | Class | Activation | Key default |
|---|---|---|---|
| Chunking | `ChunkingService` | always (ingestion) | 512 tokens / overlap 64 |
| Multi-Query | `MultiQueryService` | `spectra.multi-query.enabled` | 2 variants |
| RRF fusion | `HybridSearchService` | `spectra.hybrid-search.enabled` | k=60, topBM25=20 |
| Re-ranking | `CrossEncoderRerankerClient` + `reranker` service | `spectra.reranker.enabled` | multilingual mMiniLMv2, top 20 |
| Compression | `ContextCompressionService` | `spectra.context-compression.enabled` | — |
| Long-Context Bypass | inline in `RagService` | `spectra.long-context-rag.enabled` | ≤ 100 chunks |

> Exhaustive list of environment variables: **[Configuration](../configuration.en.md)**.
> Plain-language "ideas" version: **[Guide to the ideas and algorithms (FR)](../user/documentation-pedagogique.fr.md)**.

---

## 1. Chunking — split so you can retrieve

**The problem.** You can't vectorize a whole document. The embedding model's window is limited,
and above all: **a single vector for 50 pages dilutes meaning**. Everything ends up looking alike,
and search loses all precision. You therefore need short, coherent passages, each with its own
embedding, retrievable independently.

But a "hard" fixed-size cut breaks meaning: it separates an answer from its question, a pronoun
from its referent, a table row from its header.

**The solution.** A **hierarchical** split that respects the structure of the text:

1. first by **paragraphs** (`\n\n+`) — the natural semantic boundary;
2. a paragraph that's too large is re-split by **sentences** (`BreakIterator`, French locale);
3. a sentence that's still too large is cut by **token windows**, as a last resort.

Two important choices:

- **Size is measured in tokens, not characters.** Tokenization is exact (jtokkit,
  `CL100K_BASE` encoding), to match the *real* limit of the model rather than an approximation.
- **Chunks overlap.** The last *N* tokens of the previous chunk are carried over to the start of
  the next one: information straddling a boundary stays present, whole, in at least one chunk.
  The token-window split additionally guarantees *strictly* increasing progress, so it never
  loops on an overlap ≥ the step size.

Each chunk carries the `sourceFile` and `chunkIndex` metadata, which make per-source deletion and
update possible (delete-by-source then reindex) on the vector side.

**In the code.**
[`ChunkingService`](../../backend/src/main/java/fr/spectra/service/ChunkingService.java) ·
`spectra.pipeline.chunk-max-tokens` (default **512**) ·
`spectra.pipeline.chunk-overlap-tokens` (default **64**).

---

## 2. Multi-Query — widen recall

**The problem.** A question has only one phrasing, but the answer may be written with other words.
"What are the payment terms?" won't necessarily retrieve a chunk that talks about "settlement
deadlines." Retrieval on a single query has limited **recall**.

**The solution.** Before searching, we ask the LLM to generate *N* **reformulations** of the
question, each from a different angle (synonyms, level of generality, alternative technical term).
We run a retrieval for each variant, then **fuse** the results by removing exact duplicates: more
recall, without adding noise.

Two robustness safeguards:

- the **original question is always in first position** — so the main retrieval happens even if
  the LLM produces bad variants;
- if generation fails, the service **falls back** to the original question alone (never a blocking
  error).

**In the code.**
[`MultiQueryService`](../../backend/src/main/java/fr/spectra/service/MultiQueryService.java) ·
`spectra.multi-query.enabled` · `spectra.multi-query.query-count` (env `SPECTRA_MULTI_QUERY_COUNT`, default **2** variants).

---

## 3. RRF fusion — combining two incomparable searches

**The problem.** Spectra searches in two complementary ways:

- **vector** (semantic) — finds *meaning*, even without a shared word;
- **lexical BM25** (keywords) — finds the *exact term* ("Article 22", a reference, a code).

Each one misses what the other finds. But you can't simply add their scores: a **cosine distance**
and a **BM25 score** live on totally different, non-comparable scales. Summing them would give
nonsense.

**The solution — Reciprocal Rank Fusion.** We ignore the raw scores and fuse only the **ranks**.
Each document receives:

```
score(d) = w_vec / (k + rank_vec) + w_bm25 / (k + rank_bm25)     with k = 60
```

A document ranked high by one source (rank 1) weighs heavily; a document present in *both* lists
accumulates both contributions and rises naturally. Since only the rank matters, the method is
**robust** to score scales and requires no calibration.

An implementation detail that matters: the two searches run **in parallel** and **degrade
independently**. If the vector store goes down, we keep the BM25 results (and vice versa) instead
of losing everything — which is precisely the point of a hybrid search.

**In the code.**
[`HybridSearchService`](../../backend/src/main/java/fr/spectra/service/HybridSearchService.java) ·
`k = 60` (constant `RRF_K`) · vector weight fixed at 1.0 ·
`spectra.hybrid-search.bm25-weight` (default **1.0**) ·
`spectra.hybrid-search.top-bm25` (default **20**).

---

## 4. Cross-Encoder re-ranking — the final precision

**The problem.** Vector retrieval relies on a **bi-encoder**: the question and each document are
encoded *separately*, then compared by distance. It's fast (document embeddings are pre-computed)
and scalable, but **approximate** — the two texts never "see" each other together. As a result,
the top 20 candidates are relevant *roughly*, but poorly ordered.

**The solution.** A **Cross-Encoder** reads the pair `(question, document)` **together**, in a
single pass of the model, and produces a much more reliable relevance score. It's too expensive to
score the whole corpus — but perfect to **re-score the short list** coming out of retrieval.

Hence the classic, efficient two-stage strategy: **retrieve broad and cheap** (bi-encoder /
hybrid), then **re-rank fine and precise** (cross-encoder) to keep only the top of the basket.
If the service is unavailable, the client **falls back** to the original ordering (safe
degradation, never a hard failure).

**In the code.**
[`CrossEncoderRerankerClient`](../../backend/src/main/java/fr/spectra/service/CrossEncoderRerankerClient.java)
calls the Python microservice
[`reranker`](../../services/reranker/app.py), which loads a Cross-Encoder via `RERANKER_MODEL` —
by default in the stack the **multilingual** model `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1`
(suited to French corpora; the service's internal fallback: `ms-marco-MiniLM-L-6-v2`) ·
`spectra.reranker.top-candidates` (env `SPECTRA_RERANKER_TOP_CANDIDATES`, default **20**) ·
**30 s** timeout.

---

## 5. Context compression — densify before generating

**The problem.** Even a relevant chunk contains filler: a useful sentence drowned in a paragraph
of context. Stacking raw chunks **wastes the model's window budget** and adds noise that can
distract it from the right information.

**The solution.** For each retained chunk, we ask the LLM to **extract only the sentences directly
useful** to the question — copied as-is, without rewording — or to answer `IRRELEVANT`, in which
case the chunk is dropped. The final context is **denser**: you can fit more sources in the same
token budget, with less distraction.

To be distinguished from **Corrective RAG**, which filters *entire* chunks (relevant / irrelevant):
here we go *inside* the chunk, at the sentence level. If the LLM errs on a chunk, the original text
is kept (safe fallback). The step runs after Corrective RAG and before generation (both
non-streaming and streaming pipelines).

**In the code.**
[`ContextCompressionService`](../../backend/src/main/java/fr/spectra/service/ContextCompressionService.java) ·
`spectra.context-compression.enabled`.

---

## 6. Long-Context Bypass — skip retrieval when it's pointless

**The problem.** On a **small corpus** (a memo, a handful of fact sheets), retrieval brings
nothing: it adds latency, a call to the vector store, and a *recall risk* (missing the right
chunk) where… the whole corpus already fits in the model's window.

**The solution.** Before searching, we **count** the collection's chunks. If that number is between
1 and a threshold, we **short-circuit vector retrieval entirely** and load *all* the chunks as
context. Simpler, cheaper, and with no risk of missing information since we give everything to the
model. Above the threshold, we automatically fall back to classic RAG. If the count fails, we also
fall back to standard retrieval.

**In the code.**
Inline in [`RagService.retrieveContext`](../../backend/src/main/java/fr/spectra/service/RagService.java)
(→ `buildFullContextResult`) ·
`spectra.long-context-rag.enabled` ·
`spectra.long-context-rag.max-collection-chunks` (default **100**).

---

## See also

- **[Technical documentation §6 — RAG (FR)](technical-doc.fr.md)**: the complete implementation
  reference (SSE streaming, Adaptive / Corrective / Self / Agentic RAG, semantic deduplication).
- **[Architecture & Services](../architecture.en.md)**: every container and service in context.
- **[Guide to the ideas and algorithms (FR)](../user/documentation-pedagogique.fr.md)**: the same
  concepts in plain language, with examples.

Want to *measure* the gain of each of these steps on your corpus? Spectra includes an **A/B
ablation** bench (`RagAblationService`) that enables/disables each option and compares the
answers — see [Architecture › RagAblationService](../architecture.en.md).
