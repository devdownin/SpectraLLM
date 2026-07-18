# Changelog — Spectra

Toutes les modifications notables sont documentées dans ce fichier.
Format : [Keep a Changelog](https://keepachangelog.com/fr/1.0.0/)
Versionnage : [Semantic Versioning](https://semver.org/lang/fr/)

---

## [Non publié]

### UI — erreurs d'ingestion par fichier visibles (succès partiels)

- **Live Ingestion Stream** : une tâche terminée dont certains fichiers ont échoué n'apparaît plus comme un succès plein — la ligne passe en avertissement « N chunks · partiel » (icône et barre en couleur d'erreur) avec le détail de chaque échec (`fileErrors`) sous le fichier concerné, et un toast signale la fin de tâche partielle. Le backend remontait ces erreurs depuis l'audit ingestion/GED ; l'UI les ignorait.
- **Panneau global des tâches** : les échecs par fichier d'une ingestion partielle sont repris dans la ligne de la tâche (champ erreur), visibles depuis n'importe quelle page.

---

## [1.13.0] — 2026-07-18

### Qualité & performance — tests d'intégration ChromaDB réel, lot d'embedding ×3

- **Tests d'intégration contre un vrai ChromaDB** (`ChromaDbConsistencyIntegrationTest`, Testcontainers) : les scénarios critiques de l'audit — ré-ingestion forcée sans duplication, suppression purgeant vecteur + BM25, homonymes protégés par l'identité `sha256` — sont désormais vérifiés de bout en bout contre un conteneur `chromadb/chroma` jetable (même image que la stack). En CI le conteneur démarre automatiquement ; sans Docker le test est ignoré, ou peut viser un serveur existant via `SPECTRA_TEST_CHROMA_URL`. Ces bugs étaient invisibles aux tests unitaires (ChromaDB mocké partout).
- **Lot d'embedding par défaut 10 → 32** (`SPECTRA_EMBEDDING_BATCH_SIZE`) : 500 chunks = 16 requêtes HTTP au lieu de 50. Abaisser sur CPU très lent.
- **`SPECTRA_EMBEDDING_TIMEOUT` enfin câblé au client d'embedding** : la variable documentée n'alimentait que `spectra.pipeline.embedding-timeout-seconds`, que rien ne consommait — le client llama.cpp utilisait son propre défaut (30 s). Elle pilote désormais le timeout réel des requêtes `/v1/embeddings` (défaut relevé à 60 s pour couvrir un lot complet sur CPU lent).

### Ingestion & GED — cohérence des index, suppression unifiée, erreurs visibles (audit)

Correctifs issus de l'[audit ingestion/GED](docs/process/archive/audit-ingestion-ged.fr.md) (PR #244, #249) :

- **Ré-ingestion `force=true` = remplacement** : les anciens chunks sont purgés (ChromaDB + BM25) avant la ré-indexation — chaque force dupliquait auparavant tous les chunks du document dans les réponses. C'est aussi la voie de réparation d'un document partiellement indexé.
- **Identité `sha256` des chunks** : les suppressions/remplacements ciblent le contenu, plus le nom de fichier — deux documents homonymes ne partagent plus leur sort (repli `sourceFile` pour les chunks historiques).
- **Suppression unifiée** : `DELETE /api/documents/{sourceFile}` supprime désormais aussi la fiche GED (la dédup SHA-256 ne bloquait plus la ré-ingestion d'un document devenu invisible du RAG) ; la purge de rétention passe par la suppression complète (DB + index) au lieu de laisser les chunks servis à vie.
- **Erreurs par fichier** : nouveau champ `fileErrors` dans le suivi des tâches d'ingestion (upload, exécuteur, URLs) ; une tâche dont tous les fichiers échouent finit `FAILED` au lieu d'un faux `COMPLETED` à 0 chunk. Nouvelle colonne `ingestion_tasks.file_errors` (migration idempotente).
- **Garde-fous** : limite de taille décompressée appliquée aussi aux uploads directs ; l'ingestion URL/batch passe par le sémaphore de concurrence et la réservation in-flight (heartbeat pour les ingestions plus longues que le TTL).
- **Rétention sur la date d'archivage** : nouvelle colonne `ingested_files.archived_at` (posée à l'archivage, effacée au retour) — la purge n'éliminait plus un vieux document fraîchement archivé ; `incrementVersion` rafraîchit `ingestedAt`.
- **Score de qualité atteignant 1.0** : l'ancienne pondération plafonnait le score réel à 0.86 — un seuil d'auto-qualification ≥ 0.9 ne qualifiait jamais rien.
- **Cycle de vie `TRAINED` automatique** : en fin de fine-tuning réussi, les documents sources du dataset sont liés au modèle (`TRAINED_ON`) et avancent vers `TRAINED` via la machine à états — ces liens n'étaient posés que manuellement.
- **FTS/BM25** : le rebuild fusionne avec l'index vivant au lieu de l'écraser, l'index disque est validé contre ChromaDB (fraîcheur), le flush passe à 30 s ; réconciliation étendue à toutes les collections (GED + flux Kafka) avec gauges par collection.
- **Divers** : locale de chunking configurable (`SPECTRA_CHUNK_LOCALE`), formats `.md`/`.markdown`/`.csv` supportés, profondeur d'aplatissement JSON bornée, delete ChromaDB par filtre `where`, suppressions SQL en masse, tri des tâches, filtre tag échappé.

### Documentation — renommage kebab-case, liens réparés, référence de configuration complète

Correctifs issus de l'[audit documentation](docs/process/audit-documentation.fr.md) :

- **Convention de nommage unifiée** : les documents de `docs/` passent en kebab-case suffixé langue (`getting-started.en.md`, `technical-doc.fr.md`…) — les 30 liens internes cassés (dont toute la section Documentation des READMEs) sont réparés.
- **`getting-started` exécutable tel quel** : chemins réels (`scripts/`, `deploy/docker`, `deploy/k8s`), URL du dépôt, renvois GKE redirigés vers `deploy/k8s/README.md`.
- **Java 25 réaligné partout** : `pom.xml` et la CI, rétrogradés à 21 par un commit d'optimisation CI sans trace, reviennent à la cible **25** — conformément à la migration documentée ici même et aux images Docker (Temurin 25). Prérequis contributeur : JDK 25. Suite de tests et SpotBugs validés sous JDK 25.
- **`configuration.en.md` complet** : ~40 variables ajoutées (bloc Kafka entier, llmfit, gardes-fous d'ingestion, `SPECTRA_CHUNK_LOCALE`, reranker, évaluation…) ; `spectra.ged.auto-retrain-threshold` désormais câblée dans `application.yml` comme les autres propriétés.
- **Exactitude** : liste des formats unifiée (table de référence unique dans `technical-doc`), pipeline d'ingestion corrigé (dédup SHA-256, BM25 toujours indexé, machine à états réelle), sémantique `force`/URLs corrigée dans le manuel, section « fiche document / cycle de vie » ajoutée au manuel.

### Model Hub — GGUF orphelins supprimables, rétention de l'historique, doc à jour

- **Suppression des GGUF orphelins depuis l'UI** (`DELETE /api/models/hub/storage/files?file=…` + bouton dans le panneau Stockage) : un fichier présent dans `data/models/` mais absent du registre (déposé à la main, laissé par un incident) était visible dans le rapport de stockage mais insupprimable sans shell — la suppression de modèle exige un alias enregistré. Garde-fous : nom simple uniquement (anti-traversée), fichier directement dans `models-dir`, refus en 409 s'il est encore référencé par le registre (retirer le modèle dans ce cas).
- **Rétention de l'historique des installations** (`InstallationRetentionService`, propriété `llmfit.installations-retention-days`, env `LLMFIT_INSTALL_RETENTION_DAYS`, défaut `0` = conserver) : cron nocturne purgeant les jobs **terminaux** (COMPLETED/FAILED/CANCELLED) plus vieux que N jours — même convention que les rétentions GED et Kafka. Les jobs non-terminaux ne sont jamais purgés (la réconciliation au démarrage les traite d'abord).
- **Documentation utilisateur à jour** : le manuel (`user-manual.fr.md` § Gestion des modèles) documente le panneau Stockage (volume + cache llmfit, purge des doublons, suppression des orphelins), l'historique des installations (bouton Réessayer, rétention) et le badge du modèle actif ; la documentation pédagogique corrige « copié » → « déplacé » et décrit le cycle de vie du stockage. Variables `LLMFIT_CACHE_DIR` / `LLMFIT_INSTALL_RETENTION_DAYS` ajoutées à `.env.example` et transmises par docker-compose.

### UI — relance des installations échouées, badge modèle actif cliquable

- **Bouton « Réessayer » dans l'historique des installations** : un téléchargement FAILED ou CANCELLED se relance en un clic avec les mêmes paramètres (modèle, quantisation, auto-activation) — le job les porte déjà. Le serveur répond 409 si un téléchargement du même modèle est déjà en cours.
- **Badge du modèle actif cliquable** : le nom du modèle affiché dans le header ouvre le Playground, où l'on change de modèle actif.
- **Logique de préremplissage extraite et testée** (`lib/fineTuningPrefill.ts`) : `suggestModelName`, `resolveTrainableBase` (métadonnée → hfRepo → nom) et `shouldReplace` (« ne jamais écraser une saisie utilisateur ») sont désormais des fonctions pures couvertes par 12 tests vitest.

### UI — modèle actif visible et formulaire de fine-tuning prérempli

- **Modèle actif affiché dans le header** : le nom du modèle de chat actif apparaît désormais en clair à côté de l'indicateur « Chat » (il n'était visible qu'en infobulle), sur toutes les pages.
- **Fine-tuning : champs préremplis d'après le modèle actif** : à l'ouverture du formulaire, le nom du modèle à produire est suggéré depuis le modèle actif (`<actif>-ft`, conforme au schéma de nommage) et le **modèle de base entraînable** est résolu automatiquement — métadonnée `baseModel` d'un modèle déjà fine-tuné, correspondance `hfRepo` avec le catalogue `base_models.json`, ou alias du catalogue contenu dans le nom. Le GGUF servi n'étant pas ré-entraînable, c'est bien la base du catalogue qui est proposée, avec un bandeau « Modèle actif : … » explicitant le préremplissage. Une valeur saisie par l'utilisateur n'est jamais écrasée (seuls les défauts génériques et les suggestions précédentes le sont).

### Model Hub — cache llmfit visible et purgeable ; catalogue des bases dans le formulaire

- **Le rapport de stockage inventorie le cache llmfit** (`GET /api/models/hub/storage`, section `llmfitCache`) : chaque GGUF du cache de téléchargement (`llmfit.cache-dir`, défaut `~/.llmfit`, env `LLMFIT_CACHE_DIR`) avec sa taille et un drapeau `duplicate` quand un fichier de même nom **et** même taille existe déjà dans `data/models/`. Cet espace — doublons hérités d'avant le passage copie → déplacement, téléchargements partiels d'installations annulées — était totalement invisible.
- **Purge des doublons du cache llmfit** (`POST /api/models/hub/storage/llmfit-cache/purge` + bouton dans le panneau Stockage du Model Hub) : supprime uniquement les doublons sûrs (même nom + même taille dans `data/models/`) et conserve les téléchargements partiels (réutilisables par llmfit au prochain essai). Garde-fou : purge refusée si le cache et `data/models/` se recouvrent (les « doublons » seraient les fichiers servis eux-mêmes).
- **Fine-tuning : catalogue des bases sous le champ « Modèle de base »** : le champ propose désormais les alias de `base_models.json` (datalist avec descriptions — taille, GPU requis…) tout en conservant la saisie libre d'un repo HuggingFace complet, avec un rappel des alias valides sous le champ. Fini les 400 « modèle de base inconnu » à l'aveugle.

### Model Hub — fin du doublon du cache llmfit et des faux « COMPLETED »

- **Le GGUF téléchargé est déplacé, plus copié** (`LlmFitService.moveToSharedVolume`) : quand `llmfit` télécharge dans son propre cache (`~/.llmfit/…`), le fichier était copié vers le volume des modèles et l'original restait — chaque modèle occupait **deux fois sa taille**, et cet espace était invisible puisque le rapport de stockage n'inventorie que `data/models/`. Le fichier est désormais déplacé (`Files.move`, rename instantané sur le même système de fichiers) avec repli copie + suppression best-effort de la source (avertissement dans les logs si elle subsiste).
- **GGUF introuvable après un exit 0 = FAILED, plus COMPLETED** : si `llmfit` sortait en succès sans qu'aucun fichier `.gguf` ne soit détecté (ni dans sa sortie, ni par scan de `models-dir`), le job était marqué COMPLETED (« non enregistré ») et s'affichait **en vert** dans l'historique alors que le modèle n'était ni copié, ni enregistré, ni activable. Le job passe désormais en **FAILED** avec un message actionnable, et le flux SSE signale l'erreur au lieu d'émettre 100 % + succès.

### Déploiement k8s/GKE — le chat suit le modèle actif (fin de « modèle actif ≠ modèle servi »)

- **Superviseur piloté par le registre en k8s** : `llama-cpp-chat` lance désormais `scripts/llm-chat-entrypoint.sh` (intégré aux images `Dockerfile.llama` / `Dockerfile.llama.cuda`) au lieu de servir un fichier figé. Il lit le pointeur `active-chat-model` du volume des modèles et redémarre llama-server à chaud à chaque changement (POST /api/config/model, activation post-fine-tuning, installation llmfit auto-activée) — même convergence automatique qu'en docker-compose, plus de redéploiement manuel.
- **Volume des modèles partagé avec le chat** : `06-llama-chat.yaml` monte le PVC `models` (lecture seule) et non plus le PVC `fine-tuning` ; le modèle de chat par défaut et les modèles installés/fine-tunés vivent tous à côté du registre. `02-pvc.yaml` documente la nouvelle contrainte de co-scheduling (ou RWX multi-nœuds).
- **Variables alignées sur docker-compose** : `spectra-api-config` renseigne `SPECTRA_LLM_CHAT_FILE` / `SPECTRA_LLM_EMBEDDING_FILE` (source des modèles par défaut → pointeur servable dès le 1er boot) ; `llama-chat-config` passe aux variables du superviseur (`LLM_CHAT_MODEL_FILE/NAME`, `LLM_PORT`, `MODELS_DIR`, hints `LLM_*`, `LLM_CHAT_EXTRA_ARGS` pour le GPU). L'overlay GPU règle l'offload via `LLM_CHAT_EXTRA_ARGS` (et non plus `LLAMA_NGL`).
- **Seeding cohérent** : `k8s/seed/seed-models.yaml` télécharge le modèle de chat dans le volume `models` (et non plus dans `fine-tuning/merged/model.gguf`), avec un nom de fichier aligné sur `SPECTRA_LLM_CHAT_FILE`. Docs (`k8s/README.md`, `scripts/gke-seed-models.sh`) mises à jour.

### Model Hub — boucle « comparatif → qualité mesurée »

- **Comparaison qualité asynchrone et suivie** (`QualityBenchmarkService.submitCompare`, `QualityCompareJob`, `POST /api/quality-benchmark/compare/async`, `GET /compare/{jobId}`, `GET /compare`) : le benchmark tenu à l'écart est lent (plusieurs appels LLM par question, ×2 modèles), donc piloté comme un job de fond suivi (PENDING → RUNNING → COMPLETED/FAILED) plutôt qu'en requête HTTP bloquante. Une seule comparaison à la fois (bascule du modèle actif), persistée en JSON et réconciliée au démarrage (jobs orphelins → FAILED), à l'image d'`EvaluationService`.
- **Le Model Hub relie fit matériel et qualité réelle** : après une installation **auto-activée**, l'API mémorise le modèle actif remplacé (`InstallationJob.previousActiveModel`) et l'UI propose directement de lancer le benchmark qualité du nouveau modèle (candidate) contre le précédent (baseline) — sur **votre corpus**. Le composant `QualityBenchmarkCta` sonde le job et affiche le face-à-face (exactitude /10, taux d'hallucination, refus corrects) avec deltas et verdict. On choisit ainsi sur des chiffres mesurés, pas seulement sur le score de compatibilité de llmfit.

### Model Hub — installations persistantes (reprise après redémarrage)

- **Suivi persisté des téléchargements** (`installation_jobs` en H2, `InstallationJob`/`InstallationJobEntity`/`InstallationJobRepository`) : un redémarrage de l'API tuait le sous-processus `llmfit` et effaçait tout le suivi (les sinks SSE ne vivaient qu'en mémoire). Chaque installation est désormais persistée de bout en bout (PENDING → DOWNLOADING → REGISTERING → COMPLETED/FAILED), progression comprise — même pattern que les jobs de fine-tuning.
- **Réconciliation au démarrage** (`LlmFitService.reconcileInterruptedInstallations`, `@PostConstruct`) : tout job resté non-terminal (orphelin de l'ancienne JVM) est marqué **FAILED** (« Interrompu par un redémarrage du serveur ») au lieu de rester figé. L'historique redevient honnête.
- **Historique interrogeable** : `GET /api/models/hub/installations` (liste, plus récentes d'abord) et `GET /api/models/hub/installations/{jobId}`. Panneau repliable « Installation history » dans le Model Hub (statut, progression, erreur), rafraîchi tant qu'un téléchargement est en cours.

### Ingestion streaming Kafka — enrichir le RAG au fil de l'eau (données vivantes)

- **Consumer Kafka** (`KafkaIngestionListener`, `KafkaConfig`) : source d'ingestion continue en plus des uploads/URLs. **Désactivé par défaut** (`spectra.kafka.enabled=false`) — aucun bean Kafka créé, démarrage inchangé. Commit **manuel** des offsets après indexation (*at-least-once*), retries + **Dead Letter Topic** `<topic>.DLT`, concurrence et sécurité SASL/SSL configurables.
- **Upsert par identité métier** (`IngestionService.upsertFromStream`) : la clé du message devient `sourceFile = kafka://<topic>/<key>` ; une nouvelle version **remplace** l'ancienne (`deleteBySource` sur ChromaDB *et* BM25, puis réindexation). Valeur nulle = **tombstone** (suppression). **Idempotence** par empreinte de contenu (absorbe les rejeux). Suivi d'état en base (`kafka_stream_source` : `content_hash`, `version`, `last_updated_at`).
- **Correctif** : `ChunkingService` propage désormais `sourceFile` dans la métadonnée des chunks, quel que soit le format — `ChromaDbClient.deleteBySource` (filtre `where sourceFile == X`) ne fonctionnait auparavant que pour les fichiers TXT, cassant silencieusement la suppression/upsert côté vecteur pour PDF/DOCX/JSON/Avro/XML.
- **Mapping de champs configurable** (`KafkaPayloadMapper`) : payload brut par défaut ; `content-field` (nom simple ou pointeur JSON) pour n'indexer qu'un champ d'un événement structuré, `metadata-fields` pour recopier des champs en métadonnées.
- **Fraîcheur temporelle** : chaque chunk du flux porte `ingestedAt` et `eventTime` (timestamp Kafka), exploitables pour un filtrage/tri par récence.
- **Rétention** (`KafkaStreamRetentionService`) : cron nocturne purgeant les sources non mises à jour depuis `retention-ttl-days` jours (0 = désactivé).
- **Métriques Micrometer** : `spectra.kafka.messages{topic,result}` et `spectra.kafka.processing{topic}` sur `/actuator/prometheus`.
- **Déploiement** : profil Docker `kafka` (Apache Kafka mode KRaft mono-nœud) dans `docker-compose.yml`, variables `SPECTRA_KAFKA_*` (`.env.example`). Dépendance `spring-kafka` (gérée par le BOM Spring Boot).
- **Documentation** : `docs/design-kafka-streaming-upsert.fr.md` (design détaillé), sections dédiées dans le README, la doc technique, le manuel utilisateur et le mini-livre pédagogique.

### Évaluation — mesure des gains des enrichissements LLM

- **Ablation A/B des enrichissements** (`POST /api/ablation`, `RagAblationService`) : mesure le gain marginal du **RAG** et du **fine-tuning** de bout en bout. Contrairement à `/api/quality-benchmark` (modèle brut), chaque question du benchmark tenu à l'écart passe dans le **pipeline RAG complet**, et plusieurs configurations (**bras**) sont comparées sur le même jeu. Chaque bras reporte trois familles de métriques : génération (exactitude LLM-juge, hallucination, refus), retrieval et latence (`avgLatencyMs`, `p50LatencyMs`). Corps vide = matrice par défaut « LLM seul vs RAG » ; chaque bras peut fixer un `model` (base vs fine-tuné) et `useRag`.
- **Ablation module par module** : surcharges par requête (`RagOverrides`, tri-état) threadées dans `RagService.query`/`retrieveContext` — chaque module d'optimisation (rerank, hybride, multi-query, corrective, compression, self-RAG, adaptive, conversational) peut être forcé actif/inactif par bras pour mesurer son apport marginal. Presets `cumulative` et `leave-one-out`.
- **Métriques de retrieval déterministes** (`RetrievalMetrics`) : **Hit@k**, **MRR** et **Recall@k**, calculées sans LLM à partir des sources renvoyées et d'un champ optionnel `expectedSources` dans le benchmark JSONL. Isolent la qualité de la *récupération* de celle de la *génération*.
- **Validation** des options : chaque bras renvoie `appliedCounts` (nombre de requêtes où chaque module a réellement agi), pour confirmer que la surcharge a pris effet.
- **Écran « Optimisation »** (frontend) : page dédiée et pédagogique — explication de chaque option, presets (gain du RAG, ablation cumulative, leave-one-out, gain du fine-tuning), tableau de deltas couleur, badges des modules déclenchés et légende des métriques. **Export CSV** du tableau (valeurs brutes, BOM UTF-8 pour Excel).
- **Confiance statistique** : paramètre `runs` (répétitions par bras) → moyenne ± écart-type par métrique (`stdDev`), et **deltas non significatifs grisés** dans l'UI (≤ σ combiné) pour ne pas sur-interpréter le bruit sur un petit benchmark.
- **Coût en tokens** : `avgContextTokens` par bras (estimation déterministe du contexte injecté), en complément de la latence (bruitée) — colonne dédiée + axe du nuage coût/qualité.
- **Graphiques** (recharts) : barres d'exactitude avec barres d'erreur (±σ), nuage coût/qualité (tokens vs exactitude, frontière de Pareto), waterfall du gain marginal par module.
- Requêtes d'ablation émises à température 0 pour des deltas reproductibles.
- `QualityBenchmarkService` : extraction de `judgeAnswer`, `aggregate` et `loadBenchmark` (réutilisés par l'ablation, découplage production/notation de la réponse).
- **Benchmark annoté + corpus aligné** : `highway_benchmark.jsonl` enrichi d'`expectedSources` sur les 14 questions répondables, et nouveau corpus `examples/highway/` (4 documents : procédures, événements, nomenclature, réglementation) qui répond à ces questions — ingérez-le pour activer Hit@k/MRR/Recall@k sans configuration.

---

## [1.12.0] — 2026-06-25

### Infrastructure & déploiement

- **Migration Java 25 (LTS)** : niveau de compilation et JDK de build passés de 21 à 25 (le runtime était déjà `eclipse-temurin:25-jre`). Spring Boot 4.1 supporte le JDK 25.
- **Script de création du cluster GKE** : `scripts/gke-create-cluster.sh` — idempotent (active les APIs, crée le cluster, récupère les credentials), node pool dimensionné pour l'empreinte des manifests, node pool GPU optionnel.
- **Seeding automatique des modèles GGUF** : `k8s/seed/` + `scripts/gke-seed-models.sh` — un Job télécharge les modèles directement sur les PVC (idempotent), à la place de la copie manuelle `kubectl cp`.
- **Ingress GKE natif + TLS managé** : overlay `k8s/overlays/gke/` — `ManagedCertificate` (TLS auto, sans cert-manager), redirection HTTP→HTTPS (`FrontendConfig`), `BackendConfig` avec `timeoutSec: 3600` pour ne pas couper les flux SSE, frontend en NEG/ClusterIP.

### Observabilité

- **Alertes Prometheus + dashboard Grafana** : overlay `k8s/monitoring/` — `ServiceMonitor`, `PrometheusRule` (API down, taux 5xx, latence RAG p95, heap JVM), dashboard Grafana auto-importé. Exploite les métriques `/actuator/prometheus` (tag `application=spectrallm`) de la v0.6.
- **Pas d'HPA sur `spectra-api`** (volontaire) : le backend est *stateful* (H2 fichier, BM25 en mémoire, PVC RWO en écriture, `Recreate`) et doit rester à 1 réplica ; l'autoscaling se fait au niveau des nœuds. Rationale dans `docs/DEPLOY_GKE.md` §9.

### Documentation

- `DEPLOY_GKE.md` : nouvelles sections seeding (§7), TLS managé (§8), observabilité (§9).
- README (EN/FR) + `k8s/README.md` : section déploiement enrichie (seeding, overlays GPU/GKE/monitoring) ; correction du chemin `kubectl apply -k k8s/base`.
- Commentaires pédagogiques (Javadoc) sur les classes cœur du backend (ingestion, RAG, ChromaDB, chunking, dataset, extraction…).

### CI

- `k8s-validate` : `kustomize build` + `kubeconform` étendus aux overlays `gke`, `seed` et `monitoring`.

---

## [1.11.0] — 2026-06-25

### Nouvelles fonctionnalités — Déploiement Cloud (GKE)

- **Déploiement automatisé sur Google Kubernetes Engine** : workflow `.github/workflows/deploy-gke.yml` — authentification **Workload Identity Federation** (OIDC, sans clé JSON de compte de service), build & push des images `spectra-api` / `spectra-frontend` / `spectra-llama-cpp` vers Artifact Registry, puis `kustomize`-apply de `k8s/` sur push vers `main`. `concurrency` annule le run précédent sur le même ref ; l'étape de rollout attend `llama-cpp-embed` / `llama-cpp-chat` (timeouts généreux pour le chargement du modèle).
- **`docs/DEPLOY_GKE.md`** : guide complet de mise en place GCP (Artifact Registry, compte de service deployer, Workload Identity Federation) et liste exacte des secrets/variables à créer.
- **`Dockerfile.llama`** : nouvelle image `spectra-llama-cpp` avec l'entrypoint `llama-autostart.sh` intégré (l'ancien `--target llama_cpp_runtime` n'existait plus dans le `Dockerfile` racine).

### Nouvelles fonctionnalités — Accélération GPU (opt-in)

- **`Dockerfile.llama.cuda`** : variante CUDA de l'image llama (base `ghcr.io/ggml-org/llama.cpp:server-cuda`) avec le même entrypoint autostart.
- **Overlay kustomize `k8s/overlays/gpu/`** : patche le ConfigMap (`LLAMA_NGL=-1`) et ajoute `nvidia.com/gpu: 1` + toleration au déploiement `llama-cpp-chat`. Appliqué via `kubectl apply -k k8s/overlays/gpu`. Le déploiement reste **CPU par défaut** ; l'embedding GPU est laissé en option commentée (2ᵉ GPU requis). Section GPU ajoutée à `docs/DEPLOY_GKE.md` (création du node pool GPU, build de l'image CUDA, dépannage).

### Nouvelles fonctionnalités — Observabilité

- **Scrape Prometheus réel** : annotations `prometheus.io/scrape|port|path` sur les pods `spectra-api` (`k8s/07-spectra-api`) — `/actuator/prometheus` est désormais effectivement collecté.
- **`ObservabilityConfig`** : bean `TimedAspect` (active `@Timed` sur les beans) + `MeterRegistryCustomizer` ajoutant le tag commun `application=spectrallm` à toutes les métriques (robuste au changement de package Spring Boot 4).
- **Histogrammes** : `http.server.requests` et `spectra.rag.query` exposent des percentiles (SLO HTTP 50 ms…5 s) côté Prometheus/Grafana ; `RagService.query` annoté `@Timed("spectra.rag.query")` isole la latence RAG de l'overhead HTTP, métrique unifiée pour toutes les stratégies.

### Fiabilité — ChromaDB cosinus

- **Création de collection robuste à la version** : `ChromaDbClient.getOrCreateCollection()` crée les collections avec une configuration HNSW explicite (`space=cosine`, `ef_search=100`, `ef_construction=200`) au lieu du défaut L2 de ChromaDB. Le cosinus rend les scores de similarité interprétables sur `[0,1]` (vecteurs normalisés de llama.cpp), cohérent avec la métrique par message. Tente l'API 1.x (`configuration.hnsw`), repli sur métadonnées `hnsw:*` (versions antérieures), puis création simple — le cosinus est appliqué quelle que soit la version sans jamais casser la création (4xx → repli, autres erreurs → propagées au circuit breaker/retry). *La distance est figée à la création : les collections existantes conservent leur config (ré-ingestion requise pour basculer en cosinus).*

### Fiabilité — Kubernetes / auto-réglage

- **Fallback cgroup v1** : `llama-autostart.sh` détecte CPU et RAM en cgroup v2 *puis* v1 (`cpu.cfs_quota_us`/`cfs_period_us`, `memory.limit_in_bytes`), avec garde sur la valeur « illimité » (~`LLONG_MAX`). Couvre les node pools non-COS où l'ancien code retombait sur les ressources du nœud entier (sur-threading / contexte trop grand).
- **QoS Guaranteed** : pods `llama-cpp-embed`, `llama-cpp-chat` et `spectra-api` en `requests == limits` (init containers compris) → réservation stable, pas de throttling CPU sous contention, et l'auto-réglage voit la taille réellement allouée.

### Performance

- **`LLAMA_PARALLELISM=4`** sur `llama-embed-config` (`k8s/01-configmap`) : le serveur d'embedding démarrait avec 1 slot (vs 4 en docker-compose), sérialisant les batches et bridant l'ingestion. Gain même sur CPU.

### Améliorations frontend — GED

- **Liste de documents scrollable** : la vue groupée et la vue plate de la GED (Pipelines) sont enveloppées dans un conteneur `max-h-[70vh] overflow-y-auto` ; en-têtes de colonnes et pagination restent hors zone de défilement (en-têtes visibles).
- **Rendu paresseux natif** : `content-visibility: auto` (`.cv-auto`) sur les lignes — effet « virtualisation » sans dépendance ni refactor — et en-têtes de colonnes collants (sticky) pour les longues listes.

### CI / Tests

- **Workflow `k8s-validate`** (PR + push) : `kustomize build` de la base et de l'overlay GPU, validés par `kubeconform`. Attrape un manifeste cassé avant le merge.
- **`ChromaDbClientTest`** : via MockWebServer, vérifie que le payload de création de collection contient `configuration.hnsw.space=cosine` — verrou anti-régression (tout retrait silencieux du cosinus casse le test). Dépendance de test `okhttp3 mockwebserver` ajoutée.
- **CI GKE durcie** : authentification WIF-only (suppression du chemin `credentials_json`), variables d'environnement pour la config GCP non sensible, Node.js 22 → 24.

### Correctifs

- **k8s 05/06** : champ `entrypoint:` invalide remplacé par `command:` — le spec de conteneur était rejeté par l'API Kubernetes.
- **Comptage de chunks** : correction de cohérence du compte de chunks en fin d'ingestion.

### Documentation

- **Guide pédagogique réécrit** : `documentation-pedagogique.fr.md` réorganisé en « mini-livre » des idées et algorithmes ; cross-links EN/FR ajoutés depuis le README.

---

## [1.10.0] — 2026-06-12

### Correctifs — Chat / RAG streaming

- **SSE tokens vides (`event:token` silencieux)** : `LlamaCppChatClient` — racine identifiée dans le `ServerSentEventHttpMessageReader` de Spring qui supprime le préfixe `data: ` avant d'émettre. Le filtre `.filter(l -> l.startsWith("data: "))` ne matchait donc jamais. Corrigé : filtre remplacé par `.filter(data -> !data.equals("[DONE]"))` ; méthode de parsing renommée `extractTokenFromJson` (sans dépouillement du préfixe).
- **Toggle `useRag` ignoré** : champ `Boolean useRag` ajouté à `QueryRequest` (défaut `true`). `RagService.query()` et `queryStream()` : court-circuit vers le LLM direct quand `useRag=false`, émettant `sources:[]` puis `ragStrategy:"DIRECT"`.

### Nouvelles fonctionnalités — Résilience et opérations

- **Annulation de tâches asynchrones** : `DELETE /api/ingest/{taskId}`, `DELETE /api/dataset/generate/{taskId}`, `DELETE /api/evaluation/{evalId}`, `DELETE /api/fine-tuning/{jobId}` — endpoint d'annulation pour les 4 services async. Un `Set<String> cancelledTaskIds` est vérifié à chaque itération de boucle.
- **Nettoyage mémoire planifié** : `@Scheduled(fixedDelay = 3_600_000)` sur les 4 services — purge horaire des tâches `COMPLETED`/`FAILED`/`CANCELLED` âgées de plus d'une heure (évite la fuite mémoire des `ConcurrentHashMap`).
- **Circuit breakers** : `@CircuitBreaker(name = "chroma")` sur `ChromaDbClient.getOrCreateCollection()` et `.query()` ; `@CircuitBreaker(name = "embed")` sur `LlamaCppEmbeddingClient.embed()`. Fallbacks typés (`ChromaDbUnavailableException`, `EmbeddingUnavailableException`). Configuration Resilience4j dans `application.yml` (`sliding-window-size`, `failure-rate-threshold`, `wait-duration-in-open-state`).
- **Dégradation gracieuse du multi-query** : bloc multi-query dans `RagService` enveloppé dans un try/catch avec fallback automatique vers le retrieval simple si la génération de variantes échoue.
- **`GET /api/health/services`** : nouveau `HealthController` agrégeant les `checkHealth()` de tous les services externes (LLM chat, embedding, ChromaDB, layout-parser, reranker). `healthApi.getServices()` ajouté dans `api.ts`.

### Améliorations frontend

- **Confirmation d'ingestion active** : `Datasets.tsx` — `window.confirm()` avant de lancer la génération si une tâche d'ingestion est en cours (`PENDING` ou `PROCESSING`), pour éviter un dataset incomplet.
- **Indicateurs d'erreur par service** : `Dashboard.tsx` — `statsErrors: string[]` tracke les rejets de `Promise.allSettled`. Icône `warning` affichée à côté des headers de section concernés (`Knowledge Base`, `Documents & Annotations`, `Cycle de Personnalisation`) en cas d'échec de fetch.

### Fiabilité — Schéma base de données

- **`ddl-auto: update` → `validate`** : `application.yml` — Hibernate ne modifie plus silencieusement le schéma au démarrage. Tout écart entre entité et base provoque un échec explicite au boot.
- **`schema.sql`** : DDL complet (`CREATE TABLE IF NOT EXISTS`) des 7 tables (`ingested_files`, `ingestion_tasks`, `generation_tasks`, `article_comments`, `ged_audit_log`, `document_model_links`, `fine_tuning_jobs`). Exécuté avant la validation Hibernate (`spring.sql.init.mode: always`). Idempotent — safe sur une base existante comme sur H2 fraîche.
- **`application-dev.yml`** : profil `dev` (`SPRING_PROFILES_ACTIVE=dev`) conservant `ddl-auto: update` pour le développement d'entités ; workflow : implémenter → valider avec profil dev → reporter dans `schema.sql`.
- **Timeout upload multipart** : `TomcatUploadConfig` — `disableUploadTimeout=false` + `connectionUploadTimeout=120000 ms`. Un fichier de 50 Mo depuis un client lent ne peut plus bloquer une connexion indéfiniment.

### Tests

- **`QueryControllerTest`** (5 tests MockMvc) : `POST /api/query` — requête valide → 200, question vide → 400, champ manquant → 400 ; `POST /api/query/stream` — dispatch async + `text/event-stream`, question vide → 400.
- **`RagServiceStreamTest`** (7 tests StepVerifier) : chemin direct `useRag=false` (sources → tokens → done, `ragStrategy=DIRECT`) ; LLM erreur réactive (`Flux.error`) → `sources` puis `event:error` ; LLM exception synchrone → `event:error` seul ; ChromaDB indisponible (circuit breaker) → `event:error` seul ; embedding indisponible → `event:error` seul ; `query()` + ChromaDB down → `ChromaDbUnavailableException` propagée.

---

## [1.9.0] — 2026-04-22

### Correctifs — Bugs, sécurité, fiabilisation

#### Pipeline chat / RAG

- **Interface Utilisateur : Améliorations Pédagogiques**
  - **Playground (Mode Trace)** : Ajout d'une fenêtre modale permettant de visualiser la stratégie RAG utilisée, les optimisations déclenchées, et les extraits sources finaux envoyés au LLM.
  - **Dashboard** : Remplacement de la simple liste des évaluations récentes par un graphique d'évolution (`recharts`) permettant d'apprécier la progression de la qualité du modèle au fil des cycles de fine-tuning.
  - **Optimisation** : Remplacement des info-bulles basiques par des info-bulles riches expliquant de manière pédagogique le sens des métriques (Hit@k, MRR, Taux d'hallucination).
  - **Documentation** : Ajout d'un onglet "Théorie & Algorithmes" dans l'UI reprenant les éléments clés de la documentation pédagogique (RAG, Embeddings, Recherche Hybride, filtre Jaccard).
- **`POST /api/query/stream`** : nouvel endpoint SSE manquant — le Playground était entièrement cassé (404 à chaque message)
  - `RagService.retrieveContext()` extrait la phase retrieval (embed → ChromaDB → re-rank → build sources) ; `query()` et `queryStream()` s'appuient dessus
  - `queryStream()` émet les events SSE `sources → token* → done | error` via `LlmChatClient.chatStream()`
  - Timeout de garde `Flux.timeout(generateTimeout)` côté backend + `AbortController(120s)` côté frontend
- **Temperature & Top-P câblés** : les sliders du Playground étaient sauvegardés mais jamais transmis au LLM
  - `QueryRequest` : nouveaux champs optionnels `temperature` (0.0–2.0, défaut 0.7) et `topP` (0.0–1.0, défaut 0.9)
  - `LlmChatClient.chatStream(String, String, float, float)` : nouvelle surcharge (default fallback dans l'interface)
  - `LlamaCppChatClient` transmet `temperature` et `top_p` à llama-server

#### Ingestion

- **URL encoding** : `UrlFetcherService` — URL de browserless encodée via `UriComponentsBuilder` (fix injection via query param)
- **Validation de schéma URL** : rejet des schémas non-http/https avant tout appel réseau
- **Markdown tables** : `TextCleanerService` préserve les séparateurs de tableaux Markdown lors du nettoyage
- **ZIP depth** : `IngestionService` — limite à 3 niveaux d'imbrication pour prévenir les ZIP bombs

#### GlobalExceptionHandler

- `LlmUnavailableException` → HTTP **503** (était 500 via handler générique)
- `MethodArgumentNotValidException` → HTTP **400** avec détail champ par champ (était 500)

#### ChromaDB

- Cache `ConcurrentHashMap` nom → collectionId : élimine un aller-retour réseau par requête RAG
- `deleteBySource()` : filtre `where` ChromaDB pour ne charger que les IDs concernés (était fullscan)
- Timeouts différenciés : `TIMEOUT_ADD=60s`, `TIMEOUT_QUERY=15s`, `TIMEOUT_BULK_GET=30s`, `TIMEOUT_DEFAULT=10s`
- Null-guard sur `getOrCreateCollection()` + validation du nom (3-63 chars, pattern ChromaDB)

#### Fine-tuning & Dataset

- `DatasetGeneratorService.generatedPairs` : reset complet + réécriture du fichier JSONL à chaque `submit()` (évite l'accumulation de doublons entre runs)
- `POST /api/dataset/generate?maxChunks=N` : paramètre `maxChunks` désormais fonctionnel (était ignoré)
- Protection contre la génération concurrente : `AtomicBoolean generationRunning` → HTTP 409 si déjà en cours
- `DatasetGeneratorService` : persistance JSONL au démarrage + confiance dynamique des paires

#### Asynchrone

- `AsyncConfig` : `ThreadPoolTaskExecutor` → `SimpleAsyncTaskExecutor` avec `setVirtualThreads(true)` — les tâches `@Async` utilisent désormais les virtual threads Project Loom cohérents avec `spring.threads.virtual.enabled: true`

#### Frontend — robustesse

- **Playground** : historique localStorage limité à 50 messages + catch `QuotaExceededError`
- **Datasets** : tous les `setInterval` de polling trackés dans un `useRef` + cleanup complet au unmount du composant
- **Datasets / Comparison** : arrêt automatique du polling après 5 échecs réseau consécutifs

#### Autres correctifs

- `LlamaCppChatClient.checkHealth()` : `activeModelLoaded=false` → HTTP 200 avec status `model-not-loaded` (était HTTP 500)
- `StatusController` : utilise `LlmChatClient` (interface) + `EmbeddingClient` au lieu de `LlmClient` (legacy)
- `ConfigController.setModel()` : catch `IllegalStateException` → HTTP 400 (était 500)
- `LlmFitService.installModel()` : vérification `process.exitValue()` après timeout forcibly destroy (était NPE)
- `FineTuningService` : `ProcessBuilder.directory(workDir)` pour les scripts d'entraînement
- `DpoGenerationService` / `EvaluationService` : null-guard sur self-injection `@Lazy` (`self != null ? self : this`)
- `FineTuningRequest.baseModel` : annotation `@Pattern` pour bloquer les injections de commande

---

## [1.8.0] — 2026-04-15

### Infra — Séparation chat/embed, ChromaDB v2, healthchecks

#### Docker Compose

- `llm-server` → deux services dédiés : **`llm-chat`** (port 8081, chat) et **`llm-embed`** (port 8082, embeddings)
- Nouveau service **`model-init`** : vérifie la présence et la taille (>1 Mo) des fichiers GGUF avant de démarrer les serveurs LLM ; affiche les commandes `huggingface-cli` / `wget` et interrompt la stack si un modèle est absent
- Variables renommées : `LLM_MODEL_FILE` → `LLM_CHAT_MODEL_FILE` + `LLM_EMBED_MODEL_FILE` ; `LLM_MODEL_NAME` → `LLM_CHAT_MODEL_NAME` + `LLM_EMBED_MODEL_NAME`
- `SPECTRA_LLM_PROVIDER=llama-cpp` désormais explicite dans `.env` et `docker-compose.yml` (était absent → fallback silencieux sur `ollama`)
- `SPECTRA_LLM_CHAT_BASE_URL=http://llm-chat:8081` et `SPECTRA_LLM_EMBEDDING_BASE_URL=http://llm-embed:8082` ajoutés
- Chaîne de dépendances complète : `model-init` → `llm-chat` + `llm-embed` (healthy) + `chromadb` (started) → `spectra-api` (healthy) → `frontend`
- `docker-compose.gpu.yml` mis à jour pour surcharger `llm-chat` et `llm-embed` avec l'image CUDA
- `detect-env.sh` et `detect-env.bat` mis à jour pour inclure toutes les nouvelles variables dans le `.env` généré automatiquement

#### ChromaDB API v2

- `ChromaDbClient.java` migré de l'API v1 (supprimée, HTTP 410) vers **l'API v2**
- Toutes les URLs passent par `/api/v2/tenants/default_tenant/databases/default_database/collections/…`
- Heartbeat : `/api/v1/heartbeat` → `/api/v2/heartbeat`
- Healthcheck ChromaDB : `curl` absent de l'image → remplacé par `/dev/tcp` bash natif sur `/api/v2/heartbeat`

#### Healthchecks

- `spectra-api` : `curl` absent dans `eclipse-temurin:21-jre` → remplacé par `wget -qO-` sur `/actuator/health`
- `application.yml` : valeurs par défaut `llm-server:8081` → `llm-chat:8081` ; provider par défaut `ollama` → `llama-cpp` ; ajout des blocs `chat.base-url` et `embedding.base-url`

---

## [1.7.0] — 2026-04-12

### Ajouté — Agentic RAG / Boucle ReAct (I4)

#### I4 — Agentic RAG (boucle de raisonnement ReAct)

- `AgenticRagService` : boucle THOUGHT → ACTION (SEARCH | ANSWER) activée via `SPECTRA_AGENTIC_RAG_ENABLED=true`
- Le LLM reçoit un prompt structuré en deux formats exclusifs (`ACTION: SEARCH` + `QUERY: ...` ou `ACTION: ANSWER` + `RESPONSE: ...`)
- Sur `SEARCH` : embed de la requête affinée → retrieval vectoriel ou hybride (I2) → déduplication par texte (`Set<String>`) → enrichissement du contexte
- Sur `ANSWER` : extraction du bloc `RESPONSE:` → sortie de boucle
- Boucle bornée par `max-iterations` (défaut 3) ; fallback sur génération directe si budget épuisé
- Garde-fous : format LLM inattendu → réponse brute utilisée ; `SEARCH` sans `QUERY` → sortie propre ; contexte vide → message d'indisponibilité
- Compatible I1 (re-ranking) et I2 (hybrid search) : le pipeline d'enrichissement initial s'exécute avant la boucle ; les chunks enrichis sont transmis en entrée à `AgenticRagService`
- `RagService` injecte `Optional<AgenticRagService>` et délègue après le retrieval/re-ranking quand le bean est présent
- `QueryResponse` : nouveaux champs `agenticApplied` (boolean) et `agenticIterations` (int)
- Config : `spectra.agentic-rag.{enabled, max-iterations, initial-top-k}` + variables d'env `SPECTRA_AGENTIC_RAG_ENABLED`, `SPECTRA_AGENTIC_MAX_ITERATIONS`, `SPECTRA_AGENTIC_INITIAL_TOP_K`
- Désactivé par défaut — aucun impact sur les déploiements existants

---

## [1.6.0] — 2026-04-12

### Ajouté — Layout-Aware Parsing PDF (I3)

#### I3 — Parsing PDF avec conscience de la mise en page

- Nouveau microservice Python `docparser/` (FastAPI + `pymupdf4llm`) — convertit les PDF en Markdown structuré : titres `#`/`##`, tableaux `| col |`, listes, blocs de code
- Upgrade optionnel Docling (IBM) via `USE_DOCLING=true` (modèles IA, ~500 Mo, meilleure précision sur tableaux complexes)
- `LayoutParserClient` — client HTTP Spring WebClient multipart (`POST /parse`), timeout 120 s configurable, 50 Mo d'in-memory buffer
- `LayoutAwarePdfExtractor` — remplace `PdfExtractor` quand `spectra.layout-parser.enabled=true` ; fallback automatique vers PDFBox si docparser indisponible
- `PdfExtractor` rendu conditionnel (`@ConditionalOnProperty havingValue="false" matchIfMissing=true`) — aucun conflit de factory, comportement par défaut inchangé
- Métadonnée `parser` (valeur : `pymupdf4llm` | `docling` | `pymupdf4llm-fallback`) ajoutée à chaque chunk issu d'un PDF traité par docparser
- Métadonnée `layoutAware: true` distingue les chunks avec parsing structuré des chunks PDFBox
- Service `docparser` ajouté dans `docker-compose.yml` (port hôte **8003**, healthcheck)
- Activation : `SPECTRA_LAYOUT_PARSER_ENABLED=true` (désactivé par défaut — aucun impact sur les déploiements existants)
- Config : `spectra.layout-parser.{enabled, base-url, timeout-seconds}` + variables d'env

---

## [1.5.0] — 2026-04-12

### Ajouté — Hybrid Search BM25 + Vecteurs (I2)

#### I2 — Recherche hybride (Reciprocal Rank Fusion)

- `BM25Index` : implémentation BM25Okapi en Java pur, thread-safe (`ReentrantReadWriteLock`), tokeniseur Unicode adapté au français (accents, ligatures)
- `FtsService` : gère un index BM25 par collection ChromaDB — rebuild asynchrone depuis ChromaDB au démarrage (`@PostConstruct`), mis à jour à chaque ingestion/suppression
- `HybridSearchService` : lance en parallèle via `CompletableFuture` la recherche vectorielle (ChromaDB) et la recherche BM25 (`FtsService`), fusionne via RRF (k=60, poids BM25 configurable)
- `IngestionTaskExecutor` : appelle `FtsService.indexChunks()` après chaque ajout dans ChromaDB
- `DocumentController` : appelle `FtsService.removeBySource()` à chaque suppression
- `QueryResponse` : nouveau champ `hybridSearchApplied` (boolean) ; `Source` enrichi de `bm25Score` (Float)
- Activation : `SPECTRA_HYBRID_SEARCH_ENABLED=true` (désactivé par défaut)
- Compatible avec le re-ranking I1 : hybrid search → re-ranking Cross-Encoder s'enchaînent automatiquement si les deux sont activés
- Config : `spectra.hybrid-search.{enabled, top-bm25, bm25-weight}` + variables d'env correspondantes

---

## [1.4.0] — 2026-04-12

### Ajouté — Re-ranking Cross-Encoder (I1)

#### I1 — Re-ranking post-retrieval

- Nouveau microservice Python `reranker/` (FastAPI + `sentence-transformers`) : modèle Cross-Encoder configurable via `RERANKER_MODEL` (défaut : `cross-encoder/ms-marco-MiniLM-L-6-v2`, compatible CPU)
- `RerankerClient` interface + `CrossEncoderRerankerClient` implémentation HTTP (activée uniquement si `spectra.reranker.enabled=true`)
- `RagService` modifié : récupère `topCandidates` chunks de ChromaDB (défaut 20), les re-classe via le service Cross-Encoder, ne retient que les `maxContextChunks` meilleurs pour le LLM
- `QueryRequest` : nouveau champ `topCandidates` (1–100, défaut 20)
- `QueryResponse` : nouveau champ `rerankApplied` (boolean) ; `Source` enrichi d'un champ `rerankScore` (Float)
- Service `reranker` ajouté dans `docker-compose.yml` (port hôte **8002**, healthcheck Python)
- Activation : variable d'environnement `SPECTRA_RERANKER_ENABLED=true` (désactivé par défaut — aucun impact sur les déploiements existants)
- `SpectraProperties.RerankerProperties` : `enabled`, `baseUrl`, `model`, `timeoutSeconds`, `topCandidates` — tous configurables via `application.yml` ou variables d'environnement

### Corrigé
- `ModelHubController.installModel` : troisième argument `autoActivate=false` manquant
- `BenchmarkService.query` : appel `QueryRequest` mis à jour pour correspondre à la nouvelle signature (4 champs)

---

## [1.3.0] — 2026-04-11

### Ajouté — Observabilité, benchmarks et déploiement K8s

#### Benchmark API
- `GET /api/benchmark/embedding` : mesure le débit de vectorisation (chunks/s, ms/chunk)
- `GET /api/benchmark/llm` : mesure la latence de génération LLM pure (tokens/s, time-to-first-token)
- `GET /api/benchmark/rag` : mesure la latence RAG bout-en-bout (embed + ChromaDB + LLM)
- `GET /api/benchmark` : suite complète — retourne les 3 mesures dans un seul appel
- `BenchmarkService` : logique de mesure isolée, configuré pour ne pas impacter la production

#### SSE temps réel
- `GET /api/sse/system-load` : flux SSE émettant toutes les secondes l'utilisation CPU/heap JVM
- `GET /api/sse/training-logs` : flux SSE des logs de fine-tuning en temps réel
- `TrainingLogBroadcaster` : canal `Sinks.Many` multicast avec buffer 500 messages ; `FineTuningService` publie chaque ligne stdout/stderr du script

#### Configuration à chaud
- `GET /api/config/model` : retourne le modèle chat actif (depuis `ModelRegistryService`)
- `POST /api/config/model` : bascule le modèle chat sans redémarrage (met à jour `registry.json`)

#### Mode batch programmatique
- `BatchService` : orchestre le pipeline complet ingest-local → dataset → fine-tuning depuis le code Java
- `BatchRunner` : CLI `--batch` pour déclencher le pipeline en ligne de commande (utilisé par `pipeline.bat` / `pipeline.sh`)

#### Déploiement Kubernetes
- Manifests `k8s/` : 10 fichiers YAML + `kustomization.yaml` pour un déploiement complet sur tout cluster K8s ≥ 1.26
  - Namespace, ConfigMap, 4 PVCs, 6 Deployments (spectra-api, spectra-frontend, llm-chat, llm-embed, chromadb, browserless)
  - Ingress unique — seul le frontend est exposé ; les services internes restent en ClusterIP
- `k8s/README.md` : procédure de déploiement + commandes `kubectl apply -k` / rollback

#### Setup automatisé
- `setup.sh` / `setup.bat` : création des répertoires `data/`, détection du profil serveur (via `detect-env`), aide au téléchargement du modèle GGUF initial
- `scripts/requirements.txt` : dépendances Python versionnées (unsloth, trl, transformers, datasets, bitsandbytes, accelerate) pour le fine-tuning QLoRA

---

## [1.2.0] — 2026-04-06

### Ajouté — Fonctionnalités Axolotl (H1–H4)

#### H1 — Alignement DPO (Direct Preference Optimization)
- `DpoGenerationService` : génère des paires (choisi/rejeté) en demandant au LLM une réponse intentionnellement erronée
- Nouveaux endpoints : `POST /api/dataset/dpo/generate`, `GET /api/dataset/dpo/generate/{taskId}`, `GET /api/dataset/dpo/stats`
- `train_host.py` + `train.sh` : flag `--dpo` → `DPOTrainer` (trl), fallback SFT automatique si trl < 0.4
- Checkbox "Alignement DPO" dans le formulaire Fine-Tuning
- `FineTuningService` : export dataset DPO si `dpoEnabled=true`

#### H2 — Évaluation automatique LLM-as-a-judge
- `EvaluationService` : échantillonne 5 % du dataset (min 5, max 50 paires), interroge le modèle actif, utilise le même modèle comme juge (note 1–10 + justification JSON)
- Résultats persistés dans `evaluations.json` (survive au redémarrage)
- Nouveaux endpoints : `POST /api/evaluation`, `GET /api/evaluation`, `GET /api/evaluation/{evalId}`
- Page `Comparison.tsx` entièrement réécrite : score global, barres par catégorie, détail question/réponse/justification

#### H3 — Multipacking
- `PackedDataset` dans `train_host.py` : greedy bin-packing des séquences courtes, affiche le ratio d'utilisation
- Flag `--packing` dans `train_host.py` et variable `$8` dans `train.sh`
- `SFTTrainer` avec `packing=True` (GPU)
- Champ `packingEnabled` dans `FineTuningRequest` + checkbox dans l'interface

#### H4 — Recettes d'entraînement YAML
- `RecipeController` : `GET /api/fine-tuning/recipes`, `GET /api/fine-tuning/recipes/{name}`, `POST /api/fine-tuning/recipe/export`
- 3 recettes prédéfinies : `cpu-rapide.yml`, `gpu-qualite.yml`, `dpo-alignement.yml`
- Sélecteur de recettes dans `FineTuning.tsx` + bouton Export (télécharge `.yml`)
- Sérialisation SnakeYAML (dépendance déjà présente via Spring Boot)

### Corrigé
- `LlamaCppRuntimeOrchestrator` : `--flash-attn on` au lieu du flag nu `--flash-attn` (llama-server attend une valeur)

### Modifié
- `pipeline.bat` : support des flags `--packing` et `--dpo` (transmission à `train_host.py`)
- Documentation : `IMPROVEMENTS.md`, `README.md`, `user-manual.fr.md` mis à jour avec H1–H4

---

## [1.1.0] — 2026-04-02

### Ajouté — Migration llama-cpp
- Inférence chat et embedding migrées de Ollama vers llama-server (llama-cpp-turboquant)
- `LlamaCppChatClient` + `LlamaCppEmbeddingClient` : clients HTTP OpenAI-compatible
- `LlamaCppRuntimeOrchestrator` : auto-détection CPU/RAM/GPU → paramètres llama-server optimaux
- `GET /api/config/resources` + `POST /api/config/resources/refresh`
- Healthchecks Docker : `wget` sur `/health` (llama-server), retry avec `start_period`
- Cache KV f16 pour le serveur d'embedding
- Streaming SSE (`/api/query/stream`) : `sources` → `token*` → `done | error`
- Circuit breaker sur les appels LLM (3 tentatives, backoff exponentiel)
- Sélecteur de modèle dans le Playground

### Ajouté — Ingestion URL
- `UrlFetcherService` : HEAD → content-type → téléchargement direct (PDF/TXT) ou rendu JS (HTML via browserless/chrome)
- `POST /api/ingest/url` avec `{"urls": [...]}`
- Service `spectra-browserless` dans docker-compose.yml

### Corrigé
- Healthchecks Docker alignés sur les contraintes réelles de chaque image (pas de `curl` dans certains conteneurs)

---

## [1.0.0] — 2026-04-01

### Ajouté — Audit complet et corrections

#### Fiabilité (A1–A6)
- **A1** Persistance H2 : tâches d'ingestion, jobs fine-tuning, paires générées survivent au redémarrage
- **A2** Déduplication SHA-256 à l'ingestion (`?force=true` disponible)
- **A3** Timeout LLM configurable (`spectra.ollama.generate-timeout-minutes: 10`)
- **A4** Support `.doc` (HWPFDocument/POI Scratchpad) en plus de `.docx`
- **A5** Race condition `generatedPairs.clear()` : liste locale par tâche, fusion atomique en fin
- **A6** Pagination ChromaDB `getAllDocuments()` (limit=500/offset)

#### Performance (B1–B4)
- **B1** Cache de l'ID de collection ChromaDB (évite un aller-retour HTTP par requête)
- **B2** Parallélisation des appels LLM (résumé + classification + cas négatif en Virtual Threads)
- **B3** Taille de batch d'embeddings configurable (`spectra.ollama.embedding-batch-size`)
- **B4** Constante collection `"spectra_documents"` centralisée dans `ChromaDbProperties`

#### Qualité dataset (C1–C4)
- **C1** `sourceFile` correctement propagé dans les métadonnées ChromaDB
- **C2** Parsing JSON robuste : nettoyage balises Markdown avant extraction
- **C3** Filtre de qualité RAG : seuil distance cosinus configurable (`spectra.rag.max-distance-threshold: 0.8`)
- **C4** Équilibrage des paires par source (max 20 % par fichier source)

#### Observabilité (D1–D2)
- **D1** Métriques Micrometer/Prometheus : `spectra.ingestion.chunks.total`, `spectra.rag.query.duration`, etc.
- **D2** Logs heap mémoire rétrogradés en DEBUG

#### Sécurité (E1–E3)
- **E1** Filtre `ApiKeyFilter` sur `/api/**` si `SPECTRA_API_KEY` défini
- **E2** Limite upload : `max-file-size: 100MB`, `max-request-size: 500MB`
- **E3** Sanitisation des noms de fichiers uploadés

#### Fonctionnalités (F1–F4)
- **F1** `GET /api/documents` + `DELETE /api/documents/{sourceFile}`
- **F2** Reprise de génération interrompue (WIP JSONL + progress JSON)
- **F3** Support Avro (Apache Avro 1.12.0)
- **F4** Collections multiples ChromaDB (`?collection=` sur ingest et query)

#### Infrastructure (G1–G3)
- **G1** `.gitattributes` : LF pour scripts/java/yml, CRLF pour .bat, binaire pour .gguf
- **G2** `.gitignore` : données, modèles, artefacts Python
- **G3** `GET /api/status/deep` : healthcheck fonctionnel ChromaDB + LLM

---

## [0.9.0] — 2026-03-25

### Ajouté — Fonctionnalités initiales
- Pipeline complet : ingestion → dataset → fine-tuning → RAG
- Inférence via Ollama (phi3, mistral, llama3)
- ChromaDB v2 pour le stockage vectoriel
- Interface React (Vite + Tailwind) : Dashboard, Datasets, Fine-Tuning, Playground
- Scripts : `start.bat`, `stop.bat`, `adddoc.bat`, `pipeline.bat`
- Docker Compose multi-services
- Swagger UI

---

*Spectra — Transformez vos documents en intelligence artificielle locale.*
