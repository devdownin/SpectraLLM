# Audit — Pipeline RAG, seconde passe (chemin de requête)

> Audit du 2026-11-10. Il complète [l'audit RAG](audit-rag.fr.md) (état de l'art, R1 à R5, A1 à A8)
> et ses lots [1-2](rag-audit-lots-1-2.fr.md) et [3](rag-audit-lot-3.fr.md), sans les refaire.
> Périmètre : le code actuel du chemin de requête — `RagService` (JSON et streaming), les modules
> à appel LLM (`MultiQueryService`, `CorrectiveRagService`, `SelfRagService`, `AdaptiveRagService`,
> `ConversationalRagService`, `ContextCompressionService`), `HybridSearchService`, `BM25Index`,
> `RagPromptBudget`, `LlamaCppChatClient`, les juges (`LlmJudge`, juge A/B).
> Méthode : lecture du code, tests unitaires. **Aucune mesure de qualité n'a été exécutée** (pas de
> pile ni de modèle dans l'environnement d'audit) : les corrections ci-dessous réparent des défauts
> de logique ; leur effet sur les scores reste à mesurer avec le banc.
>
> **Constat général.** Le chemin principal (retrieval, fusion, reranking, budget, citations) est
> cohérent et bien couvert. Les défauts sont dans les **modules optionnels à appel LLM** : ils
> décidaient par tirage aléatoire, sur la mauvaise question en conversation, et écartaient des
> passages que le modèle n'avait pas jugés. S'y ajoutent deux défauts de mesure (profondeur
> lexicale, consigne de langue dans la question).

## Constats corrigés

### Q1 — Les appels de décision tiraient au sort (température 0,7) — élevé

Routage adaptatif, tri des passages (Corrective RAG), reformulations (multi-query, conversation,
requête corrective), réflexion Self-RAG, compression et **les deux juges** (`LlmJudge`, juge A/B)
passaient par `chat(système, message)`, dont le défaut est la température de conversation (0,7,
`top_p` 0,9). Le commentaire de `SelfRagService` croyait l'inverse (« défauts du provider, le
grading devant être aussi déterministe que possible »).

Conséquences : un même passage jugé pertinent puis hors sujet d'une requête à l'autre ; une même
question routée vers deux pipelines ; des notes de juge bruitées. Pour le banc, deux mesures du
même réglage divergeaient sans que rien n'ait changé — l'étape 5 avait rendu la *génération*
évaluée déterministe, pas son *juge*.

**Correction.** `LlmChatClient.decide` et `decideJson` (température 0, `top_p` 1) ; les neuf
appels de décision y passent. La génération de la réponse garde les paramètres de la requête.

### Q2 — Un passage sans verdict était écarté comme hors sujet — élevé

`CorrectiveRagService.parseGrades` ne gardait que les passages notés `RELEVANT` ou `AMBIGUOUS`.
Si le modèle ne rendait qu'une partie des lignes (sortie tronquée, ligne oubliée : courant avec
un 7B sur dix passages), les passages **sans ligne** disparaissaient du contexte. Le repli « tout
garder » ne jouait que si *aucune* ligne n'était lisible. De plus, l'ordre rendu était celui des
lignes du modèle, pas celui du classement : la numérotation `[n]` et la priorité du budget de
prompt en dépendaient.

**Correction.** Seul un `IRRELEVANT` explicite écarte un passage ; les indices sont rendus dans
l'ordre du classement d'entrée ; un avertissement compte les passages sans verdict.

### Q3 — En conversation, les modules jugeaient la question elliptique — élevé

Le Conversational RAG reformule « et pour le second ? » en question autonome pour le retrieval et
le reranker. Mais le tri correctif, la compression de contexte et la réflexion Self-RAG recevaient
la question **brute**. Les passages retrouvés grâce à la reformulation étaient ensuite jugés contre
une question qui ne dit rien sans l'historique, donc écartés ou compressés à tort.

**Correction.** Ces trois modules reçoivent la question autonome, sur les chemins JSON et
streaming. Le message de génération garde la question d'origine et l'historique.

### Q4 — La profondeur lexicale ne suivait pas le nombre de candidats — moyen

`HybridSearchService` interrogeait BM25 à profondeur fixe (`SPECTRA_HYBRID_BM25_TOP`, 20) quel que
soit `topCandidates`. À 50 candidats, seule la branche vectorielle s'élargissait : la fusion RRF
comparait 50 rangs denses à 20 rangs lexicaux. La série « 20 contre 50 candidats » de
[l'étape 2](../tech/enterprise-rag-stage2.fr.md) mesurait donc surtout le vectoriel.

**Correction.** Profondeur lexicale = max(`top-bm25`, candidats demandés). Sans effet aux réglages
par défaut (20 candidats, ou 5 passages sans reranker).

### Q5 — « Answer in English. » dans la question polluait la recherche — moyen

Les consignes du prompt RAG sont en français et le pipeline standard n'avait aucun réglage de
langue de réponse (seul l'Agentic RAG en a un). Le banc contournait en ajoutant
« Answer in English. » à chaque question — texte qui partait aussi dans l'embedding, la requête
BM25 (termes `answer`, `english`), le reranker, les reformulations et le tri correctif.

**Correction.** `SPECTRA_RAG_RESPONSE_LANGUAGE` (`auto`, `fr`, `en` ; vide par défaut = prompt
inchangé) ajoute la consigne en fin de prompt système, au service seulement : les règles partagées
avec le dataset de fine-tuning (contrat F14) ne changent pas. Côté banc,
`enterprise_rag_experiment.py run --question-suffix ""` retire le suffixe. Le défaut du banc est
conservé pour rester comparable aux runs existants ; **mesurer les deux** avant de trancher.

### Q6 — Reformulations multi-query : langue et numérotation — moyen

Le prompt (en français) ne demandait pas de garder la langue de la question : sur une question
anglaise, des reformulations françaises ne retrouvent rien en BM25 et peu en vectoriel avec un
embedding peu multilingue. Les puces et numéros ajoutés malgré la consigne (« 1. », « - ») partaient
tels quels dans l'embedding et la requête lexicale ; deux variantes identiques n'étaient pas
dédoublonnées.

**Correction.** Consigne « dans la langue de la question », marqueurs de liste retirés, doublons
écartés.

### Q7 — Streaming et JSON divergeaient sur le mode agentique — faible

Avec le routage adaptatif **et** `spectra.agentic-rag.always=true`, une question classée
`STANDARD` passait en agentique sur le chemin JSON et en pipeline standard en streaming
(affectation simple au lieu d'un « ou »). **Correction :** même règle des deux côtés.

### Q8 — Une réponse coupée par la fenêtre était rendue comme complète — moyen

`LlamaCppChatClient` ne lisait pas `finish_reason`. Le budget de prompt repose sur une estimation
(caractères / 3,5) ; sur un texte dense (identifiants, tableaux, code), le prompt réel dépasse
l'estimation et la réserve de sortie (500 tokens) fond. La réponse tronquée n'était ni signalée ni
comptée. **Correction :** avertissement journalisé et compteur (`truncatedCompletions()`), sur les
appels non diffusés. Le streaming et l'exposition dans la réponse d'API restent ouverts (O5).

### Q9 — Message d'abstention trompeur — faible

Sans passage retenu, le chemin JSON répondait « Veuillez d'abord ingérer des documents via POST
/api/ingest » — y compris quand la base est pleine et que le seuil d'abstention ou un filtre a
écarté les passages ; le streaming ne le disait pas. **Correction :** même message neutre des deux
côtés.

## Constats ouverts

| # | Constat | Pourquoi ce n'est pas corrigé ici |
|---|---|---|
| O1 | Le tri correctif ne lit que les **400 premiers caractères** de chaque passage, la réflexion Self-RAG les 300 premiers des **3 premiers** passages. Un passage pertinent après le caractère 400 est jugé sur son début | Élargir coûte des tokens sur chaque requête : arbitrage à mesurer (ablation) |
| O2 | Mots vides anglais minimaux en BM25 (`what`, `which`, `how`, `does`, `on`, `was`… absents) : les mots de question pèsent dans le score lexical d'un corpus anglais | Change l'analyse : `ANALYZER_VERSION` à incrémenter, index reconstruit, baseline modifiée. À mesurer comme levier |
| O3 | La fusion multi-query, le retrieval correctif et l'expansion par entités dédoublonnent par **texte** : deux documents au passage identique fusionnent, la source du second disparaît | Demande d'identifier les passages par identifiant dans `SingleQueryResult` (refonte locale) |
| O4 | La déduplication s'applique après la coupe à `maxContextChunks`, sans complément : le contexte rétrécit | Choix de conception à mesurer ; le reranker ne rend que `maxContextChunks` résultats |
| O5 | Troncature (Q8) non détectée en streaming et non exposée au client ; estimation de tokens par caractères | Le tokenizer du serveur (`/tokenize`) donnerait un compte exact, au prix d'un appel par requête |
| O6 | Messages d'abstention et de citations non étayées en français, quelle que soit la langue de réponse | À traiter avec Q5 si `response-language` est adopté |
| O7 | La reformulation conversationnelle n'est pas contrôlée : si le modèle **répond** au lieu de reformuler, sa réponse sert de requête | Heuristique à définir (longueur, forme interrogative) et à tester |
| O8 | Racinisation du « -x » final : `index` → `inde`, `linux` → `linu` (collision avec « Inde ») | Même coût que O2 (changement d'analyse) |
| O9 | Routage `AGENTIC` sans service agentique : la réponse annonce `ragStrategy=AGENTIC` alors que le pipeline standard a répondu | Cosmétique, à corriger avec le prochain changement du DTO |

## Effet sur les étapes d'optimisation

- **Q1** conditionne la reproductibilité de tout ce qui utilise un module LLM ou un juge local :
  série `multiquery` de l'étape 2, audit du fine-tuning (étape 5).
- **Q4** change ce que mesure la série `candidates` de l'étape 2 (les deux branches s'élargissent).
- **Q5** ajoute une variante à mesurer : question seule + `SPECTRA_RAG_RESPONSE_LANGUAGE=en`
  contre question + suffixe. La baseline v1 reste définie avec le suffixe.
- Ces corrections modifient le comportement des modules optionnels : rejouer la baseline n'est pas
  nécessaire (modules désactivés), sauf à 50 candidats (Q4).

## Vérifications

- Suite backend : 1 523 tests, 0 échec ; 11 erreurs dans `UrlFetcherServiceTest`, qui résout
  `example.com` par DNS et ne peut pas passer dans l'environnement d'audit (sans rapport).
- Nouveaux tests : décodage glouton envoyé au serveur (`temperature` 0, `top_p` 1), verdicts
  partiels et ordre du tri correctif, question autonome transmise au tri (JSON et streaming),
  profondeur lexicale, variantes multi-query, consigne de langue (prompt inchangé sans réglage),
  compteur de réponses tronquées, suffixe de question du banc.
- Non vérifié : tout effet sur la qualité des réponses.
