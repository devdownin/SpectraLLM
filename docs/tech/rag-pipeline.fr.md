# Le pipeline RAG — pourquoi chaque étape

> **Rôle de ce document** — le **raisonnement de conception** (le « pourquoi » de chaque
> étape). Les faits d'implémentation (défauts, limites) font foi dans
> [technical-doc.fr.md](technical-doc.fr.md) ; la vue d'ensemble est dans
> [architecture.en.md](../architecture.en.md).

La qualité des réponses de Spectra ne vient pas du modèle seul : elle vient de **ce qu'on lui
donne à lire**. Le pipeline de récupération (retrieval) est donc le cœur du produit. Ce document
explique le **raisonnement** derrière chaque étape — le *pourquoi* avant le *comment* — puis
renvoie à la classe qui l'implémente et à ses paramètres réels.

Chaque module ci-dessous est **activable indépendamment** par configuration. Un déploiement
minimal n'utilise que le retrieval vectoriel + la génération ; on ajoute les étapes une à une
selon le corpus et le budget de latence. Le chef d'orchestre est
[`RagService`](../../backend/src/main/java/fr/spectra/service/RagService.java).

## Vue d'ensemble

Ordre d'exécution (les modules optionnels sont entre crochets) :

1. **[Adaptive RAG]** classe la requête → réponse directe · standard · agentique
2. **[Conversational RAG]** réécrit la question avec l'historique de la conversation
3. **[Long-Context Bypass]** si le corpus est petit, charge tout — *sans* retrieval → §6
4. **[Multi-Query]** génère *N* reformulations, un retrieval par variante, fusion → §2
5. **Retrieval** : vectoriel pur, ou **[Hybrid Search]** BM25 + vecteurs fusionnés par **RRF** → §3
6. **[Re-ranking]** un Cross-Encoder re-note la liste courte → §4
7. **[Semantic Dedup]** retire les chunks quasi-identiques (Jaccard)
8. **[Corrective RAG]** élimine les chunks jugés non pertinents (grading LLM)
9. **[Context Compression]** extrait les passages utiles à l'intérieur de chaque chunk → §5
10. **[Agentic RAG]** boucle de raisonnement ReAct si la stratégie est *agentique*
11. **[Self-RAG]** génère puis s'auto-évalue et raffine si besoin
12. **Génération** de la réponse par le LLM

En amont de tout cela, à l'**ingestion**, le texte est découpé en chunks → §1.

| Étape | Classe | Activation | Défaut clé |
|---|---|---|---|
| Chunking | `ChunkingService` | toujours (ingestion) | 512 tokens / overlap 64 |
| Multi-Query | `MultiQueryService` | `spectra.multi-query.enabled` | 2 variantes |
| Fusion RRF | `HybridSearchService` | `spectra.hybrid-search.enabled` | k=60, topBM25=20 |
| Re-ranking | `CrossEncoderRerankerClient` + service `reranker` | `spectra.reranker.enabled` | mMiniLMv2 multilingue, top 20 |
| Compression | `ContextCompressionService` | `spectra.context-compression.enabled` | — |
| Long-Context Bypass | inline dans `RagService` | `spectra.long-context-rag.enabled` | ≤ 100 chunks |

> Liste exhaustive des variables d'environnement : **[Configuration](../configuration.en.md)**.
> Version « idées » en langage clair : **[Guide des idées et des algorithmes](../user/documentation-pedagogique.fr.md)**.

---

## 1. Le chunking — découper pour pouvoir retrouver

**Le problème.** On ne peut pas vectoriser un document entier. La fenêtre du modèle d'embedding
est limitée, et surtout : **un vecteur unique pour 50 pages dilue le sens**. Tout finit par se
ressembler, et la recherche perd toute précision. Il faut donc des passages courts et cohérents,
chacun avec son propre embedding, retrouvable indépendamment.

Mais une coupe « sèche » à taille fixe casse le sens : elle sépare une réponse de sa question, un
pronom de son référent, une ligne de tableau de son en-tête.

**La solution.** Un découpage **hiérarchique** qui respecte la structure du texte :

1. d'abord par **paragraphes** (`\n\n+`) — la frontière sémantique naturelle ;
2. un paragraphe trop grand est re-découpé par **phrases** (`BreakIterator`, locale française) ;
3. une phrase encore trop grande est coupée par **fenêtres de tokens**, en dernier recours.

Deux choix importants :

- **La taille est mesurée en tokens, pas en caractères.** La tokenization est exacte (jtokkit,
  encodage `CL100K_BASE`), pour coller à la *vraie* limite du modèle plutôt qu'à une approximation.
- **Les chunks se chevauchent** (overlap). On reporte les derniers *N* tokens du chunk précédent
  au début du suivant : une information à cheval sur une frontière reste présente, entière, dans
  au moins un chunk. Le découpage par fenêtres de tokens garantit en plus une progression
  *strictement* croissante, pour ne jamais boucler sur un overlap ≥ à la taille du pas.

Chaque chunk porte les métadonnées `sourceFile` et `chunkIndex`, qui rendent possibles la
suppression et la mise à jour *par source* (delete-by-source puis réindexation) côté vecteur.

**Dans le code.**
[`ChunkingService`](../../backend/src/main/java/fr/spectra/service/ChunkingService.java) ·
`spectra.pipeline.chunk-max-tokens` (défaut **512**) ·
`spectra.pipeline.chunk-overlap-tokens` (défaut **64**).

---

## 2. Multi-Query — élargir le rappel

**Le problème.** Une question n'a qu'une seule formulation, mais la réponse peut être rédigée
avec d'autres mots. « Quels sont les délais de paiement ? » ne récupérera pas forcément un chunk
qui parle d'« échéances de règlement ». Le retrieval sur une seule requête a un **rappel** limité.

**La solution.** Avant de chercher, on demande au LLM de générer *N* **reformulations** de la
question, chacune sous un angle différent (synonymes, niveau de généralité, terme technique
alternatif). On lance un retrieval pour chaque variante, puis on **fusionne** les résultats en
supprimant les doublons exacts : plus de rappel, sans ajouter de bruit.

Deux garde-fous de robustesse :

- la **question originale est toujours en première position** — le retrieval principal a donc
  lieu même si le LLM produit de mauvaises variantes ;
- si la génération échoue, le service **retombe** sur la seule question originale (jamais d'erreur
  bloquante).

**Dans le code.**
[`MultiQueryService`](../../backend/src/main/java/fr/spectra/service/MultiQueryService.java) ·
`spectra.multi-query.enabled` · `spectra.multi-query.query-count` (env `SPECTRA_MULTI_QUERY_COUNT`, défaut **2** variantes).

---

## 3. La fusion RRF — combiner deux recherches incomparables

**Le problème.** Spectra cherche de deux façons complémentaires :

- **vectorielle** (sémantique) — trouve le *sens*, même sans mot commun ;
- **lexicale BM25** (mots-clés) — trouve le *terme exact* (« Article 22 », une référence, un code).

Chacune rate ce que l'autre trouve. Mais on ne peut pas simplement additionner leurs scores :
une **distance cosinus** et un **score BM25** vivent sur des échelles totalement différentes et
non comparables. Les sommer donnerait n'importe quoi.

**La solution — Reciprocal Rank Fusion.** On ignore les scores bruts et on ne fusionne que les
**rangs**. Chaque document reçoit :

```
score(d) = w_vec / (k + rang_vec) + w_bm25 / (k + rang_bm25)     avec k = 60
```

Un document bien classé par une source (rang 1) pèse lourd ; un document présent dans les *deux*
listes cumule les deux contributions et remonte naturellement. Comme seul le rang compte, la
méthode est **robuste** aux échelles de score et ne demande aucune calibration.

Détail d'implémentation qui compte : les deux recherches tournent **en parallèle** et **dégradent
indépendamment**. Si le vector store tombe, on garde les résultats BM25 (et inversement) au lieu
de tout perdre — c'est précisément l'intérêt d'une recherche hybride.

**Dans le code.**
[`HybridSearchService`](../../backend/src/main/java/fr/spectra/service/HybridSearchService.java) ·
`k = 60` (constante `RRF_K`) · poids vectoriel fixé à 1,0 ·
`spectra.hybrid-search.bm25-weight` (défaut **1,0**) ·
`spectra.hybrid-search.top-bm25` (défaut **20**).

---

## 4. Le re-ranking Cross-Encoder — la précision finale

**Le problème.** Le retrieval vectoriel repose sur un **bi-encodeur** : la question et chaque
document sont encodés *séparément*, puis comparés par distance. C'est rapide (les embeddings des
documents sont pré-calculés) et scalable, mais **approximatif** — les deux textes ne se « voient »
jamais ensemble. Résultat : les 20 premiers candidats sont pertinents *en gros*, mais mal ordonnés.

**La solution.** Un **Cross-Encoder** lit la paire `(question, document)` **ensemble**, dans un
seul passage du modèle, et produit un score de pertinence bien plus fiable. Il est trop coûteux
pour scorer tout le corpus — mais parfait pour **re-noter la liste courte** issue du retrieval.

D'où la stratégie en deux temps, classique et efficace : **récupérer large et pas cher**
(bi-encodeur / hybride), puis **re-classer fin et précis** (cross-encoder) pour ne garder que le
haut du panier. En cas d'indisponibilité du service, le client **retombe** sur l'ordre d'origine
(dégradation sûre, jamais d'échec dur).

**Dans le code.**
[`CrossEncoderRerankerClient`](../../backend/src/main/java/fr/spectra/service/CrossEncoderRerankerClient.java)
appelle le microservice Python
[`reranker`](../../services/reranker/app.py), qui charge un Cross-Encoder via `RERANKER_MODEL` —
par défaut dans la stack le modèle **multilingue** `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1`
(adapté aux corpus français ; repli interne du service : `ms-marco-MiniLM-L-6-v2`) ·
`spectra.reranker.top-candidates` (env `SPECTRA_RERANKER_TOP_CANDIDATES`, défaut **20**) ·
timeout **30 s**.

---

## 5. La compression de contexte — densifier avant de générer

**Le problème.** Même un chunk pertinent contient du remplissage : une phrase utile noyée dans un
paragraphe de contexte. Empiler des chunks bruts **gaspille le budget de la fenêtre** du modèle et
ajoute du bruit qui peut le distraire de la bonne information.

**La solution.** Pour chaque chunk retenu, on demande au LLM d'**extraire uniquement les phrases
directement utiles** à la question — copiées telles quelles, sans reformulation — ou de répondre
`IRRELEVANT`, auquel cas le chunk est éliminé. Le contexte final est plus **dense** : on peut y
faire tenir davantage de sources pour le même budget de tokens, avec moins de distraction.

À distinguer du **Corrective RAG**, qui filtre des chunks *entiers* (pertinent / non pertinent) :
ici on descend *à l'intérieur* du chunk, au niveau de la phrase. En cas d'erreur LLM sur un chunk,
le texte original est conservé (fallback sûr). L'étape s'exécute après le Corrective RAG et avant
la génération (pipeline non-streaming).

**Dans le code.**
[`ContextCompressionService`](../../backend/src/main/java/fr/spectra/service/ContextCompressionService.java) ·
`spectra.context-compression.enabled`.

---

## 6. Le Long-Context Bypass — sauter le retrieval quand il ne sert à rien

**Le problème.** Sur un **petit corpus** (une note de service, une poignée de fiches), le retrieval
n'apporte rien : il ajoute de la latence, un appel au vector store, et un *risque de rappel*
(rater le bon chunk) là où… tout le corpus tient déjà dans la fenêtre du modèle.

**La solution.** Avant de chercher, on **compte** les chunks de la collection. Si ce nombre est
compris entre 1 et un seuil, on **court-circuite entièrement le retrieval vectoriel** et on charge
*tous* les chunks comme contexte. Plus simple, plus économique, et sans risque de manquer une
information puisqu'on donne tout au modèle. Au-delà du seuil, on repasse automatiquement au RAG
classique. Si le comptage échoue, on retombe aussi sur le retrieval standard.

**Dans le code.**
Inline dans [`RagService.retrieveContext`](../../backend/src/main/java/fr/spectra/service/RagService.java)
(→ `buildFullContextResult`) ·
`spectra.long-context-rag.enabled` ·
`spectra.long-context-rag.max-collection-chunks` (défaut **100**).

---

## Voir aussi

- **[Documentation technique §6 — RAG](technical-doc.fr.md)** : la référence d'implémentation complète
  (streaming SSE, Adaptive / Corrective / Self / Agentic RAG, déduplication sémantique).
- **[Architecture & Services](../architecture.en.md)** : chaque conteneur et service en contexte.
- **[Guide des idées et des algorithmes](../user/documentation-pedagogique.fr.md)** : les mêmes
  concepts en langage clair, avec des exemples.

Vous voulez *mesurer* le gain de chacune de ces étapes sur votre corpus ? Spectra intègre un banc
d'**ablation A/B** (`RagAblationService`) qui active/désactive chaque option et compare les
réponses — voir [Architecture › RagAblationService](../architecture.en.md).
