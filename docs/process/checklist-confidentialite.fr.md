# Checklist — confidentialité et exploitation (local et air-gapped)

> Audit du 2026-11-10. Périmètre : sorties réseau, journaux, dépendances et persistance des données
> (Qdrant et fichiers de `data/`). Méthode : lecture du code et de la configuration Compose effective,
> contrôles automatiques rejouables (`scripts/privacy_audit.py`), mesures d'audit de dépendances.
>
> **Statut global : validée sur plan, à confirmer sur un hôte réel.** Aucune pile ne tournait dans
> l'environnement d'audit (ni Docker, ni modèles, ni trafic réseau mesurable). Chaque ligne indique donc ce
> qui est *prouvé par un contrôle rejouable*, ce qui est *établi par lecture*, ce qui reste *ouvert*, et ce
> que seul un hôte réel peut confirmer (§ 5, procédure prête à l'emploi).

| Marque | Sens |
|---|---|
| ✅ A | Contrôle automatique rejouable, exécuté en CI (`pytest scripts/tests`) |
| ✅ L | Établi par lecture du code ou mesure ponctuelle, décrit ici |
| 🔧 | Écart trouvé et **corrigé** dans cette livraison |
| ⚠️ | Écart **ouvert** : décision ou action nécessaire |
| ⏳ | À valider sur un hôte réel (§ 5) |

## 1. Réseau : aucune sortie en mode local ou air-gapped

| # | Contrôle | État | Preuve |
|---|---|---|---|
| R1 | Le code d'exécution n'appelle aucun hôte Internet autre que Hugging Face (Model Hub) | ✅ A | `privacy_audit.py egress` contre `deploy/airgap/egress-allowlist.txt` : un hôte nouveau casse le contrôle |
| R2 | Cet appel unique est refusé hors ligne | 🔧 ✅ A | `SPECTRA_OFFLINE=true` : `LlmFitService.installModel` répond 403, `StartupOrchestrator` n'installe rien (`LlmFitServiceTest`, `StartupOrchestratorTest`) |
| R3 | Le rattrapage de modèles au démarrage se désactive | 🔧 ✅ L | `SPECTRA_STARTUP_AUTO_INSTALL_MODELS` n'atteignait **pas** le conteneur (liste `environment:` explicite) : une valeur posée dans `.env` était sans effet. Désormais transmise, avec `SPECTRA_OFFLINE`, `SPECTRA_LOG_CONTENT` et `HF_ENDPOINT` |
| R4 | Un miroir Hugging Face est respecté | 🔧 ✅ L | La recherche de dépôt GGUF codait `https://huggingface.co` et contournait `HF_ENDPOINT` ; elle le lit maintenant (`spectra.model-hub.endpoint`) |
| R5 | L'isolement est imposé par Docker, pas par le code | 🔧 ✅ A | `docker-compose.airgap.yml` : réseau `default` en `internal: true`, aucun port publié sauf le frontend (boucle locale). `privacy_audit.py airgap` lit `docker compose config` |
| R6 | Interface sans CDN ni police distante | ✅ L | `@fontsource/*` et `material-symbols` embarqués ; aucun hôte dans `frontend/src`, `index.html`, `nginx.conf` (couvert par R1) |
| R7 | Pas de télémétrie des composants | ✅ A/L | Qdrant : `QDRANT__TELEMETRY_DISABLED=true` (contrôlé par `airgap`). llama.cpp : entrypoints sans `curl`/`wget`/`-hf`. Bibliothèques Python : `HF_HUB_OFFLINE=1`, `HF_HUB_DISABLE_TELEMETRY=1`, `DO_NOT_TRACK=1` dans l'overlay |
| R8 | Pas de téléchargement natif à l'exécution (DJL) | ✅ L | Le jar `tokenizers-0.36.0` embarque `libtokenizers` pour linux x86_64 et aarch64 (le repli `publish.djl.ai` ne sert qu'aux autres plateformes) ; ONNX Runtime embarque ses natifs |
| R9 | Aucun port exposé au réseau local par défaut | ✅ L | `docker compose config` : tout en `127.0.0.1` ; Qdrant n'est **pas** publié du tout |
| R10 | Sources web, S3, Kafka, browserless | ⚠️ | Ce sont des hôtes que vous configurez (contrôles SSRF et `SPECTRA_SOURCES_ALLOWED_HOSTS` existants). En air-gap, ne renseigner que des hôtes internes ; le réseau interne bloque le reste |
| R11 | Binaire tiers `llmfit` (`recommend`, `download`) | ⏳ | Binaire fermé installé à la construction : son comportement réseau n'est pas auditable par lecture. À tracer sur hôte (§ 5), et n'utiliser `download` qu'avec un miroir |
| R12 | Construction d'image hors ligne | ⚠️ | `deploy/docker/Dockerfile` télécharge l'installateur `llmfit` et les images de base : construire sur une machine connectée, puis transférer les images. `LLMFIT_SHA256` est vide par défaut (installateur non vérifié) |
| R13 | La suite de tests tourne hors ligne | ⚠️ | `UrlFetcherServiceTest` (11 tests) résout `example.com` par DNS : il échoue sans réseau (constaté ici). À simuler (`InetAddress`) pour une chaîne de construction air-gapped |

**Hôte Windows ou macOS Intel hors conteneur** : DJL peut alors tenter `publish.djl.ai` (R8). Utiliser la pile Docker.

## 2. Journaux

| # | Contrôle | État | Preuve |
|---|---|---|---|
| J1 | Aucune question, reformulation ou URL brute dans les journaux | 🔧 ✅ A | 12 occurrences corrigées (Adaptive, Conversational, Corrective et Multi-query RAG, Ablation, Benchmark, ingestion et récupération d'URL). `privacy_audit.py logs` échoue sur tout nouvel appel `log.*` qui reçoit `question`, `query`, `prompt`, `answer`, `url`… hors `LogSafe` ; exception explicite : commentaire `privacy-ok: <raison>` |
| J2 | Ce qui reste permet de diagnostiquer | ✅ A | `LogSafe.text` : longueur et empreinte HMAC à clé aléatoire par processus (corrélation entre lignes, pas de dictionnaire, pas de comparaison entre redémarrages). `LogSafe.url` : sans identifiants, requête ni fragment (`LogSafeTest`) |
| J3 | Contenu intégral uniquement sur demande explicite | ✅ A | `SPECTRA_LOG_CONTENT=true` (désactivé par défaut) ; à ne pas laisser en production |
| J4 | Métriques sans donnée d'utilisateur | ✅ L | Étiquettes Micrometer relevées : `engine`, `outcome`, `tool`, `operation`, `status`, `mode`, `topic` (nom de topic Kafka configuré). Aucun texte d'utilisateur |
| J5 | Questions non persistées en base | ✅ L | `schema.sql` : aucune table de questions ni de réponses ; seuls fiches GED, tâches, commentaires et audit GED |
| J6 | Rétention des journaux | ⚠️ | `data/logs/spectra.log` : 30 jours, 3 Go, valeurs **codées en dur** dans `logback-spring.xml` (non réglables par variable). Réduire ou chiffrer le volume si nécessaire |
| J7 | Noms de fichiers et condensats SHA-256 | ⚠️ | Journalisés (données d'exploitation). Un nom de fichier peut être sensible : non masqué |
| J8 | Aucun résidu constaté à l'exécution | ⏳ | `scripts/privacy_runtime_check.sh [--compose-logs]` : question à marqueur unique, puis recherche dans `data/logs` et dans les journaux du conteneur |

## 3. Dépendances

| # | Contrôle | État | Preuve |
|---|---|---|---|
| D1 | Java (Maven) | ✅ L | OWASP dependency-check en CI (`dependency-scan.yml`, `dependency-suppression.xml`) |
| D2 | Frontend (npm) | 🔧 ✅ L | `npm audit --omit=dev` : 2 vulnérabilités **hautes** (`zod` < 4.6.6, `follow-redirects` 1.16.0–1.16.1) corrigées par montée de version du seul lockfile (`zod` 4.6.7, `follow-redirects` 1.16.3) → 0 vulnérabilité ; 497 tests Vitest et `tsc` passent |
| D3 | Service `docparser` (Python) | 🔧 ✅ L | `python-multipart` 0.0.20 : 6 avis (`pip-audit`). Passé à 0.0.33 → aucun avis ; 10 tests du service passent |
| D4 | Service `trainer` (Python) | ✅ L | `pip-audit` : aucun avis connu |
| D5 | Service `reranker` (Python) | ⚠️ | `pip-audit` : `sentence-transformers` 5.5.0 (PYSEC-2026-4164, corrigé en 5.6.0), `torch` 2.12.0 (PYSEC-2025-194, 2.13.0), `transformers` 5.8.0 (PYSEC-2026-3929, corrigé en 5.10.0 ; PYSEC-2026-4174). Non corrigé ici : une montée de `torch`/`transformers` change les scores du reranker et exige de rejouer la parité (tests de parité ONNX). Service optionnel, réseau interne, chargé d'un seul modèle fixe ; lire les avis sur osv.dev avant de classer le risque |
| D6 | Images de base épinglées | ⚠️ | Java et Python : par condensat. `frontend/Dockerfile` : `node:22-alpine` et `nginx:alpine` flottants ; images Compose (Qdrant, llama.cpp, Kafka) épinglées par **étiquette**, pas par condensat |
| D7 | Code récupéré à l'exécution | ✅ L | Convertisseur GGUF à révision épinglée (`llama_cpp_convert.py`) ; installateur `llmfit` : voir R12 |
| D8 | Dépendance sans épinglage | ⚠️ | `scripts/requirements.txt` : `torch` sans version (choix CPU/GPU) pour l'entraînement sur l'hôte |
| D9 | Aucun contrôle Python, npm ou image en CI | ⚠️ | Seul Maven est scanné. Ajouter `pip-audit`, `npm audit --omit=dev` et un scan d'images (résoudre d'abord D5) |

## 4. Persistance des données

| Donnée | Emplacement | Contenu en clair | Effacement |
|---|---|---|---|
| Chunks et vecteurs | `data/qdrant` | texte des documents (charge utile `document`), métadonnées, vecteurs | `DELETE /api/documents/{fichier}` ou GED ; voir Q3 |
| Fiches GED, tâches, commentaires, audit | `data/spectra-db.mv.db` (H2) | noms de fichiers, SHA-256, commentaires | suppression du document |
| Archives GED | `data/archive/<sha>/manifest.json` | métadonnées d'archivage | supprimé avec le document |
| Journaux | `data/logs` | voir § 2 | rotation (J6) |
| Jeux d'entraînement et adaptateurs | `data/dataset`, `data/fine-tuning` | **extraits de documents**, paires Q/R | non liés à la suppression d'un document (Q6) |
| Fichiers temporaires d'ingestion | `/tmp` du conteneur | document en cours | supprimés après usage ; balayage horaire des orphelins |
| Modèles | `data/models`, caches Hugging Face | poids | sans donnée d'utilisateur |

| # | Contrôle | État | Preuve |
|---|---|---|---|
| Q1 | Qdrant inaccessible depuis le réseau local | ✅ L | Aucun port publié ; réseau Compose seul |
| Q2 | Qdrant sans clé d'API par défaut | ⚠️ | Acceptable sur un réseau interne à une pile. Hôte partagé : activer `QDRANT__SERVICE__API_KEY` et `SPECTRA_QDRANT_API_KEY` (le support est dans l'application ; l'overlay `docker-compose.qdrant-pilot.yml` montre le câblage) |
| Q3 | Un document supprimé disparaît du disque | ⏳ | La suppression retire points, index BM25, manifeste et fiche, avec tombstone et reprise (`GedService.deleteDocument`, `wait=true`). Mais Qdrant ne purge **physiquement** un point supprimé qu'à l'optimisation des segments, et ses seuils par défaut peuvent ne jamais la déclencher sur un petit index : le texte peut subsister dans les segments ou le journal. `privacy_runtime_check.sh` le mesure (marqueur unique, avant et après suppression) et refuse de conclure si le marqueur n'était pas visible avant |
| Q4 | Garantie d'effacement | ✅ L | Arrêter la pile, supprimer `data/qdrant`, ré-ingérer : seul remède garanti sans réglage de l'optimiseur |
| Q5 | Chiffrement au repos | ⚠️ | Aucun, par conception : tout est en clair sous `data/`. Chiffrer le volume (LUKS, BitLocker, FileVault) pour un poste nomade ou partagé |
| Q6 | Un modèle affiné « retient » les documents | ⚠️ | Supprimer un document n'efface ni les jeux d'entraînement dérivés ni les poids. La table `document_model_links` trace le lien : un effacement complet impose de ré-entraîner ou retirer le modèle |
| Q7 | Aucun instantané Qdrant créé par l'application | ✅ L | Pas d'appel `snapshots` dans `QdrantStore` ; une sauvegarde manuelle de `data/` recopie tout le texte en clair |
| Q8 | Suppression partielle reprise | ✅ L | Un échec laisse le document en `DELETION_PENDING` ; reprise toutes les 60 s |

## 5. Validation sur un hôte réel (à exécuter, non exécutée ici)

```bash
# A. Isolement imposé (mode air-gapped)
docker compose --project-directory . -f deploy/docker/docker-compose.yml \
  -f deploy/docker/docker-compose.airgap.yml up -d
python3 scripts/privacy_audit.py airgap
docker network inspect spectrallm_default --format '{{.Internal}}'          # true
docker compose exec spectra-api sh -c 'timeout 5 bash -c "echo > /dev/tcp/1.1.1.1/443"; echo exit=$?'   # exit != 0

# B. Aucune sortie en mode local (pile standard) : capturer 10 minutes d'usage normal
#    (ingestion, questions, génération) et ne garder que ce qui sort du réseau privé.
sudo tcpdump -ni any -w /tmp/spectra.pcap \
  'not (dst net 10.0.0.0/8 or dst net 172.16.0.0/12 or dst net 192.168.0.0/16 or dst net 127.0.0.0/8)'
#    Attendu : zéro paquet, hors résolution DNS de l'hôte. Rejouer avec SPECTRA_OFFLINE=true puis
#    en lançant `llmfit recommend` (R11).

# C. Résidus dans les journaux et dans le stockage après suppression
SPECTRA_API_KEY=... scripts/privacy_runtime_check.sh --compose-logs        # code 0 attendu
```

Code de sortie de C : `0` conforme, `1` résidu (voir le remède affiché), `2` pile injoignable ou contrôle non
probant.

## 6. Signature

| Bloc | Conforme sur plan | Reste à faire |
|---|---|---|
| Réseau | R1–R9 | R10–R13 (décisions) ; B de § 5 sur hôte |
| Journaux | J1–J5 | J6, J7 (décisions) ; J8 sur hôte |
| Dépendances | D1–D4, D7 | D5, D6, D8, D9 |
| Persistance | Q1, Q4, Q7, Q8 | Q2, Q5, Q6 (décisions) ; Q3 sur hôte |

La checklist est **validée sur le plan statique** : ce qui est écrit et configuré ne laisse aucune sortie réseau
non déclarée, ne journalise plus de texte d'utilisateur et ne dépend plus d'avis connus, sauf les écarts ⚠️
ci-dessus. Elle est **à confirmer à l'exécution** pour les lignes ⏳, qui ont chacune une commande dans § 5.

| Validé par | Date | Hôte | Résultat de A, B, C |
|---|---|---|---|
| | | | |
