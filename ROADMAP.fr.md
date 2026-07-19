# Roadmap — Spectra

Cap produit et technique de Spectra, organisé par **horizon**. Ce document complète le
[CHANGELOG](CHANGELOG.md) (ce qui est **fait**) en décrivant ce qui est **prévu** ou **envisagé**.

> **Comment lire cette roadmap**
> - **Court terme** — engagé ou imminent, dérivé de la section `[Non publié]` du CHANGELOG et des
>   suivis déjà identifiés dans les audits.
> - **Moyen terme** — direction assumée, non planifiée au jour près.
> - **Long terme** — vision et paris structurants, sujets à arbitrage.
>
> Les horizons sont des **intentions**, pas des dates contractuelles. Les items marqués d'une
> référence (`S1`, `[12]`…) renvoient à un constat documenté :
> [audit sécurité](docs/process/audit-securite.fr.md) et
> [suivi fiabilité](docs/process/reliability.fr.md). Une contribution ou une discussion sur
> n'importe quel item est bienvenue — voir [CONTRIBUTING](CONTRIBUTING.md).

---

## Vision

Spectra transforme un corpus documentaire privé en une IA experte, **de bout en bout et
100 % locale** : ingestion → RAG cité → fine-tuning → déploiement d'un GGUF exécutable partout,
sans cloud ni clés d'API. La trajectoire vise trois choses :

1. **Approfondir la qualité** du RAG et du fine-tuning (mesurer, pas seulement produire).
2. **Élargir les entrées** (connecteurs, formats, multimodal) sans casser la promesse « privé ».
3. **Durcir l'exploitation** (authentification réelle, multi-utilisateur, montée en charge) pour
   passer du poste de travail à l'équipe, puis à l'organisation.

---

## Court terme

Fondations à solidifier avant d'élargir. Plusieurs de ces points sont des **décisions
d'architecture** déjà identifiées mais non tranchées.

### Sécurité & authentification — trancher le modèle d'accès
Le modèle actuel est binaire (une clé partagée, ou rien), désactivé par défaut, sans identité par
utilisateur, et son activation casse le temps réel (SSE). C'est le chantier prioritaire.
- **Fail-closed en production** (`S2`) : refuser de démarrer en profil `prod` sans `SPECTRA_API_KEY`,
  ou au minimum un avertissement `WARN` très visible à chaque démarrage + documentation en tête de
  *Getting Started*. Correctif faible, à faire indépendamment de la refonte auth.
- **Auth compatible SSE** (`S3`) : jeton court en paramètre de requête ou cookie de session
  `HttpOnly` pour que `EventSource` fonctionne clé activée. Débloque le centre de tâches, les logs
  de fine-tuning et la charge système derrière l'authentification.
- **Exempter `OPTIONS` du filtre clé API** (`S4`) : correctif trivial pour le préflight CORS.
- **Actuator `when-authorized`** (`S5`/`[10]`) : ne plus exposer `show-details: always` ni
  `prometheus`/`metrics` à un anonyme ; réserver au réseau de scrape via NetworkPolicy.

### Observabilité
- **Correlation ID dans les logs** (`[11]`) : `MDC.put("taskId", …)` en début de tâche async,
  `MDC.clear()` en `finally`, pour désentrelacer les ingestions concurrentes.

### Qualité & fiabilité
- **Génération de dataset bornée en mémoire** (`[9]`) : la génération charge encore tout le corpus
  en RAM (la liste des sources, elle, pagine déjà) — passer à un traitement paginé/streamé.

---

## Moyen terme

Une fois la direction d'authentification tranchée, on ouvre le produit à l'équipe et on mesure la
qualité au lieu de seulement la produire.

### Authentification réelle & multi-utilisateur
- **Spring Security + OIDC/JWT** (`S1`) : comptes, rôles (RBAC), principal authentifié. L'**acteur**
  des mutations GED et de l'audit trail est **dérivé du principal**, jamais du paramètre `?actor=` —
  la traçabilité devient probante. Ce chantier règle `S1` **et** `S3` simultanément.
- **Séparation des privilèges** : lecture / ingestion / fine-tuning / administration distinctes, pour
  qu'un utilisateur ne puisse pas purger le stockage ou supprimer des modèles par défaut.
- **Rate limiting** (`S7`) : limitation par IP/clé sur les endpoints LLM coûteux (`/query`,
  `/dataset/generate`, `/dpo/generate`, commentaires IA, `/ingest`) pour éviter la saturation
  GPU/CPU et l'amplification de coût en cloud.

### Qualité du RAG — mesurer
- **Harnais d'évaluation RAG** : métriques de type RAGAS (fidélité, pertinence du contexte,
  rappel, exactitude des réponses) sur des jeux de questions/réponses de référence, exécutables en
  CI pour détecter les régressions de qualité de retrieval.
- **Cache de requêtes** : mémoïsation des embeddings de requête et des résultats de retrieval pour
  les questions récurrentes, avec invalidation à la ré-ingestion.
- **Choix d'embeddings** : rendre le modèle d'embedding sélectionnable (multilingue, domaine
  spécifique) et documenter l'impact sur la qualité.

### Ingestion — connecteurs & OCR
- **Connecteurs de sources** : SharePoint / OneDrive, Confluence, Google Drive, S3/MinIO, en plus
  des fichiers et des flux Kafka existants — avec **synchronisation incrémentale** (upsert par
  hash, déjà présent côté Kafka) plutôt que ré-ingestion complète.
- **OCR pour PDF scannés** : le pipeline docparser gère la mise en page ; ajouter un étage OCR pour
  les documents image afin de couvrir les archives numérisées.

### Fine-tuning & modèles
- **Registre de modèles** : versionner les GGUF produits avec leur lignage (corpus, dataset,
  hyperparamètres, scores d'évaluation), au-delà de la suppression par nom actuelle.
- **Options de quantization** : exposer les niveaux (Q4/Q5/Q8…) au moment de l'export GGUF, avec un
  comparatif taille/qualité intégré à l'évaluation existante.

### Migrations de schéma
- **Outil de migration versionné** (`[3]`) : introduire Flyway ou Liquibase **si** les évolutions de
  schéma dépassent les simples ajouts de colonnes idempotents couverts aujourd'hui par `schema.sql`.

---

## Long terme

Paris structurants qui changent la forme du produit. Ils supposent des arbitrages et ne sont pas
engagés.

### Montée en charge multi-instance
Aujourd'hui `spectra-api` est **volontairement mono-instance** (`[12]`) : index BM25 en mémoire,
registres de tâches, H2 fichier et fan-out SSE ne sont pas partagés entre réplicas. Lever cette
contrainte, **si** le multi-instance devient un objectif, implique :
- une **base partagée** (PostgreSQL) à la place de H2 fichier ;
- un **index de recherche distribué** (ou externalisé) au lieu du BM25 en mémoire ;
- un **bus de tâches / broadcast partagé** (registre de tâches + fan-out SSE) pour que n'importe
  quel réplica serve n'importe quel flux temps réel.

### Multi-tenancy & collaboration
- **Espaces de travail isolés** : plusieurs corpus/collections cloisonnés par équipe ou par projet,
  au-dessus de l'authentification multi-utilisateur du moyen terme.
- **Collaboration** : partage de conversations, annotations d'équipe, revue des réponses approuvées
  qui alimentent la boucle d'apprentissage continu.

### RAG avancé
- **GraphRAG / graphe de connaissances** : extraction d'entités et de relations pour le raisonnement
  multi-sauts, en complément du retrieval vectoriel/BM25 et de la boucle agentique ReAct actuelle.
- **Multimodal** : indexation et interrogation d'images, schémas et tableaux au-delà du texte extrait.

### Écosystème & extensibilité
- **API/SDK publics & système de plugins** : points d'extension pour parseurs, stratégies de
  retrieval, backends d'inférence et connecteurs, afin que la communauté branche ses propres
  composants sans forker.
- **Traçage distribué** (OpenTelemetry) : au-delà des correlation IDs, une observabilité de bout en
  bout ingestion → retrieval → inférence → fine-tuning.
- **Internationalisation** : élargir au-delà du couple FR/EN de l'interface.

---

## Hors périmètre (pour l'instant)

Pour clarifier le cap, Spectra **ne** vise **pas** à court/moyen terme :
- un **service SaaS hébergé** — la promesse est le déploiement local/air-gapped ;
- une **dépendance à des API LLM propriétaires** (OpenAI, Anthropic…) dans le chemin critique ;
- l'entraînement de **modèles de fondation** à partir de zéro — Spectra spécialise des modèles
  existants (QLoRA/DPO), il n'en pré-entraîne pas.

---

*Cette roadmap est vivante : elle évolue avec le projet et les retours. Les priorités peuvent être
réordonnées à tout moment ; le [CHANGELOG](CHANGELOG.md) reste la source de vérité de ce qui a
effectivement été livré.*
