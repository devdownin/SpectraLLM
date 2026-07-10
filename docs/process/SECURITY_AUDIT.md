# Audit de sécurité — SpectraLLM

**Périmètre** : application Spectra (API Spring Boot 4 / Java 25, frontend React/Nginx,
micro-services Python `docparser`/`reranker`, orchestration Docker Compose & Kubernetes/GKE).
**Type** : RAG + fine-tuning LLM auto-hébergé, ingestion de documents et d'URL, GED.
**Date** : 2026-07-08
**Nature** : revue de code statique + revue de configuration infra (aucun test dynamique/pentest exécuté).

> **Message clé** : le code applicatif est globalement soigné du point de vue sécurité
> (protections SSRF, XXE, zip-bomb, injection d'arguments, comparaison à temps constant
> déjà en place). Le risque dominant est **le modèle d'exposition** : l'API n'a par défaut
> **aucune authentification**, aucune autorisation par rôle, aucune limitation de débit, et
> l'infrastructure (Kubernetes, browserless, TLS) est livrée dans une posture permissive.
> Une instance déployée « telle quelle » est entièrement pilotable par quiconque atteint le port.

---

## Synthèse des risques

| # | Finding | Sévérité | Domaine |
|---|---------|----------|---------|
| 1 | Authentification API désactivée par défaut, clé unique partagée, aucune autorisation | **Critique** | AuthN/AuthZ |
| 2 | `browserless` = pivot SSRF côté serveur (validation advisory, `--no-sandbox`, sans token, sans egress policy) | **Élevée** | SSRF / infra |
| 3 | Aucune `NetworkPolicy` ni `securityContext` durci sur les pods K8s | **Élevée** | Infra / durcissement |
| 4 | Actuator exposé sans authentification (`health show-details: always`, `metrics`, `prometheus`, `info`) | **Élevée** | Fuite d'information |
| 5 | Aucune limitation de débit / de ressources sur les endpoints coûteux (ingestion, fine-tuning, RAG) | **Élevée** | DoS / abus |
| 6 | Chaîne d'approvisionnement : `curl \| sh` au build, images `:latest` non figées | **Moyenne** | Supply chain |
| 7 | TLS non activé par défaut (Ingress, Kafka `PLAINTEXT`), en-têtes de sécurité HTTP absents (nginx) | **Moyenne** | Transport / navigateur |
| 8 | Piste d'audit falsifiable : `actor` = paramètre libre non authentifié | **Moyenne** | Traçabilité |
| 9 | Injection de prompt indirecte (RAG sur documents/URL non fiables) — risque inhérent non atténué | **Moyenne** | Sécurité LLM |
| 10 | Base H2 fichier, identifiants par défaut (`sa`/vide), données au repos non chiffrées | **Faible** | Données au repos |
| 11 | Spécification OpenAPI/Swagger exemptée d'authentification (cartographie de l'API) | **Faible** | Fuite d'information |
| 12 | Validation de format manquante sur `sha256` (path variable) et `collection` | **Faible** | Robustesse |

---

## Points forts constatés (à préserver)

Ces contrôles sont **déjà correctement implémentés** — ils sont listés pour éviter toute régression :

- **SSRF (fetch direct)** : `UrlFetcherService` valide schéma + hôte, refuse loopback / privées /
  link-local / CGNAT / IPv6 ULA (métadonnées cloud `169.254.169.254` bloquées), **valide l'IP réelle
  au moment de la connexion** (`doAfterResolve`) fermant la fenêtre de DNS-rebinding, et **ne suit pas
  les redirections 30x**. Très bon travail.
- **XXE** : `XmlExtractor` désactive DOCTYPE et entités externes générales/paramètres.
- **Zip-bomb / zip-slip** : profondeur d'imbrication bornée (`MAX_ZIP_DEPTH=3`), nombre d'entrées borné
  (`maxZipEntries`), taille décompressée bornée par entrée.
- **Injection d'arguments** vers sous-processus : `LlmFitService` impose une allowlist stricte
  (`SAFE_MODEL_ID`, `SAFE_OPTION`) et `ProcessBuilder(List)` évite tout shell.
- **Comparaison de clé API à temps constant** (`MessageDigest.isEqual`).
- **Conteneur `spectra-api`** exécuté en utilisateur non-root, purge des paquets de build.
- **Nettoyage des noms de fichiers** GGUF (`safeFileName`) avant écriture disque.
- **Frontend** : rendu Markdown via `react-markdown` **sans `rehype-raw`** → pas d'injection HTML brute
  (protégé contre le XSS stocké via contenu de documents / commentaires IA).

---

## Détail des findings

### 1. Authentification désactivée par défaut & modèle d'autorisation absent — **Critique**

**Fichiers** : `src/main/java/fr/spectra/config/ApiKeyFilter.java`, `k8s/base/01-configmap.yaml:92`,
`.env.example`, `docker-compose.yml`.

- Si `SPECTRA_API_KEY` est vide (**valeur par défaut** en Docker Compose, `.env.example` et dans le
  `Secret` K8s : `SPECTRA_API_KEY: ""`), le filtre laisse **toutes les requêtes passer** en clair
  (« mode développement »). Un déploiement par copie du template expose donc l'intégralité de l'API :
  ingestion, suppression de documents (`DELETE /api/documents/{sourceFile}`, `DELETE /api/ged/...`),
  changement du modèle LLM actif (`POST /api/config/model`), lancement de fine-tuning
  (`POST /api/fine-tuning`), installation de modèles, exports, etc.
- Même **activée**, l'authentification repose sur **une clé statique unique et partagée** : pas
  d'utilisateurs, pas de rôles, pas de séparation lecture/écriture/administration, pas de rotation,
  pas de révocation. Tout porteur de la clé est administrateur.
- `shouldNotFilter` utilise `path.startsWith("/actuator")` sans frontière de segment : `/actuatorXYZ`
  serait aussi exempté (impact faible ici, mais motif fragile à corriger).

**Impact** : prise de contrôle complète de l'instance par un acteur réseau (destruction de corpus,
détournement du modèle servi, consommation CPU/GPU, exfiltration du contenu ingéré).

**Recommandations** :
- **Fail-safe par défaut** : refuser le démarrage en profil `prod` si aucune clé/authentification
  n'est configurée (au lieu d'ouvrir silencieusement l'API). Réserver le « mode ouvert » au profil `dev`.
- Introduire une authentification réelle (OIDC/JWT via un reverse-proxy ou Spring Security) avec
  **au moins deux rôles** : lecture (query) vs administration (ingestion, suppression, config, fine-tuning).
- À défaut immédiat : documenter que `SPECTRA_API_KEY` **doit** être renseignée et faire échouer le
  déploiement K8s si le Secret est vide.

---

### 2. `browserless` — pivot SSRF côté serveur — **Élevée**

**Fichiers** : `src/main/java/fr/spectra/service/UrlFetcherService.java:120-144`,
`k8s/base/04-browserless.yaml`.

- Le chemin **HTML** de l'ingestion d'URL délègue le rendu à `browserless`
  (`GET {browserless}/content?url=...`). La validation anti-SSRF de `validateUrl()` n'est **qu'advisory**
  pour ce chemin (le code le documente honnêtement) : c'est **browserless** qui résout et charge l'URL,
  hors du `HttpClient` durci. Le DNS-rebinding et l'accès aux services internes/métadonnées cloud restent
  donc possibles via ce chemin.
- Le déploiement browserless tourne avec `--no-sandbox --disable-setuid-sandbox`, **sans token**
  (`TOKEN`), sans limite d'egress, et est atteignable depuis le réseau du cluster.

**Impact** : un attaquant qui soumet une URL peut faire émettre à browserless des requêtes vers des
cibles internes (métadonnées cloud, services d'admin, ChromaDB, Kafka), potentiellement lisibles via le
contenu rendu.

**Recommandations** :
- **NetworkPolicy d'egress** stricte sur le pod browserless : n'autoriser que l'Internet public,
  **bloquer** les plages RFC1918, link-local (`169.254.0.0/16`) et les services du namespace.
- Activer un `TOKEN` browserless et le transmettre depuis `UrlFetcherService`.
- Appliquer côté navigateur une liste de blocage d'IP internes / envisager un proxy sortant filtrant.

---

### 3. Durcissement Kubernetes absent — `securityContext` & `NetworkPolicy` — **Élevée**

**Fichiers** : `k8s/base/*.yaml` (aucun `securityContext`, aucune `NetworkPolicy`,
seul `k8s/seed/seed-models.yaml` en définit un pour un Job).

- Aucun `podSecurityContext` / `securityContext` conteneur : pas de `runAsNonRoot`, pas de
  `readOnlyRootFilesystem`, pas de `allowPrivilegeEscalation: false`, pas de `capabilities: drop: [ALL]`,
  pas de `seccompProfile`. Les pods `chromadb`, `browserless`, `llama-*`, `frontend` peuvent tourner en root.
- **Aucune `NetworkPolicy`** : réseau intra-namespace totalement ouvert → mouvement latéral libre
  (ChromaDB, Kafka, llama servers accessibles de n'importe quel pod compromis, y compris browserless).

**Recommandations** :
- Ajouter un `securityContext` durci sur chaque Deployment (`runAsNonRoot: true`,
  `readOnlyRootFilesystem: true` là où possible, `drop: [ALL]`, `seccompProfile: RuntimeDefault`).
- Adopter le **Pod Security Standard `restricted`** au niveau du namespace.
- Définir des `NetworkPolicy` par défaut *deny-all* puis autoriser explicitement les flux
  (`frontend → spectra-api`, `spectra-api → {chromadb, llama-*, browserless}`, egress browserless restreint).

---

### 4. Actuator exposé sans authentification — **Élevée**

**Fichier** : `src/main/resources/application.yml:222-242`, `ApiKeyFilter.java:50`.

- `/actuator/**` est **explicitement exempté** du filtre de clé API, tout en exposant
  `health,info,prometheus,metrics` avec `management.endpoint.health.show-details: always`.
- Résultat : `/actuator/health` (détails des composants, chemins, état des dépendances, disque),
  `/actuator/metrics`, `/actuator/prometheus` et `/actuator/info` sont **lisibles anonymement**.
  Combiné à l'absence de NetworkPolicy et au proxy nginx `/api/`, cela facilite la reconnaissance
  (versions, topologie interne, noms de collections, volumétrie).

**Recommandations** :
- Passer `show-details` à `when-authorized`.
- Servir l'actuator sur un **port de management séparé** non exposé publiquement, ou le protéger
  (auth dédiée / restriction réseau). Ne conserver publiquement que `health/liveness` et `health/readiness`.

---

### 5. Absence de limitation de débit et de quotas — **Élevée**

**Fichiers** : contrôleurs `IngestController`, `FineTuningController`, `QueryController`,
`ModelHubController`, `UrlIngestionService`.

- Aucun rate-limiting applicatif. Des endpoints très coûteux sont ouverts : ingestion de fichiers
  (jusqu'à 50 Mo/fichier, 100 Mo/requête), ingestion d'URL (rendu navigateur), requêtes RAG (appels LLM),
  **fine-tuning** (sous-processus CPU/GPU longue durée), installation de modèles (téléchargements réseau).
- Le fine-tuning est certes sérialisé (`trainingRunning`), mais un flot de requêtes non authentifiées
  peut saturer CPU/GPU/disque/réseau et provoquer un déni de service, voire remplir les volumes.

**Recommandations** :
- Rate-limiting par client (bucket4j, ou limites nginx/Ingress `limit_req`) en priorité sur
  `ingest`, `ingest/url`, `fine-tuning`, `query`, `models/hub/install`.
- Quotas de stockage et alertes sur remplissage des PVC (`documents`, `dataset`, `fine-tuning`, `models`).
- Concurrence bornée déjà présente sur l'ingestion — étendre le principe (file d'attente + rejet 429).

---

### 6. Chaîne d'approvisionnement — **Moyenne**

**Fichiers** : `Dockerfile:18-20`, `docker-compose.yml` (`chromadb/chroma:latest`,
`browserless/chrome:latest`), `k8s/base/04-browserless.yaml` (`:latest`).

- `RUN curl -fsSL https://llmfit.axjns.dev/install.sh | sh` exécute un script tiers **non épinglé**
  au build → compromission de ce domaine = exécution de code arbitraire dans l'image.
- Plusieurs images en `:latest` (chromadb, browserless) → builds non reproductibles, pas de contrôle
  d'intégrité, mises à jour silencieuses potentiellement vulnérables.

**Recommandations** :
- Épingler `llmfit` à une version + vérifier une somme de contrôle/signature (ou vendoriser le binaire).
- Épingler toutes les images par **digest** (`image@sha256:...`).
- Le projet a déjà `dependency-check`, CodeQL, Scorecard et Dependabot (bon) : ajouter le **scan
  d'images de conteneurs** (Trivy/Grype) et la génération de SBOM au CI.

---

### 7. TLS & en-têtes de sécurité — **Moyenne**

**Fichiers** : `k8s/base/09-ingress.yaml` (bloc `tls` commenté), `frontend/nginx.conf`,
`application.yml` (Kafka `security-protocol: PLAINTEXT` par défaut).

- L'Ingress est livré **sans TLS** (sections `tls`/cert-manager commentées) → trafic et clé API en clair.
- `nginx.conf` ne pose **aucun en-tête de sécurité** : pas de `Content-Security-Policy`,
  `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, ni HSTS.
- Kafka par défaut en `PLAINTEXT` (acceptable en dev, à durcir en prod : `SASL_SSL`).

**Recommandations** :
- Rendre TLS obligatoire (cert-manager/Let's Encrypt) et rediriger 80→443.
- Ajouter les en-têtes de sécurité + une CSP restrictive dans `nginx.conf` ; activer HSTS derrière TLS.

---

### 8. Piste d'audit falsifiable — **Moyenne**

**Fichiers** : `GedController.java` (`@RequestParam(defaultValue = "api") String actor`),
`ArticleCommentController.java`, `GedService.audit(...)`.

- L'`actor` enregistré dans l'audit trail (`AuditLogEntity`) provient d'un **paramètre de requête libre**,
  non lié à une identité authentifiée. N'importe qui peut écrire `actor=admin` ou usurper un tiers.
- La traçabilité (R6/R7 « audit trail ») est donc **non fiable** en l'état.

**Recommandation** : dériver `actor` de l'identité authentifiée (sujet du token) une fois l'AuthN en place,
et ignorer/écraser le paramètre côté serveur.

---

### 9. Injection de prompt indirecte (RAG) — **Moyenne**

**Fichiers** : pipeline d'ingestion (`IngestionService`, `UrlFetcherService`) → `RagService`,
`AgenticRagService`, `ArticleCommentService.generateAiComment`.

- Spectra ingère des documents et des URL **non fiables**, puis réinjecte leur contenu dans le contexte du
  LLM (RAG, RAG agentique, génération de commentaires, génération de dataset de fine-tuning). Un document
  piégé peut contenir des instructions détournant la génération (indirect prompt injection),
  potentiellement **empoisonner le dataset de fine-tuning** (persistance).
- Risque inhérent au RAG, non spécifique à un bug — mais aucun garde-fou (séparation de rôles dans le prompt,
  filtrage, marquage du contenu récupéré comme données et non instructions) n'est visible.

**Recommandations** :
- Cloisonner clairement, dans les templates de prompt, le **contenu récupéré** (données) des **instructions
  système** ; ajouter des consignes anti-override.
- Revue humaine avant qu'un commentaire IA / une paire générée n'entre dans le dataset SFT/DPO
  (le champ `minConfidence`/évaluation APPROVED existe déjà — le rendre bloquant).
- Journaliser/limiter les sorties d'outils dans le RAG agentique.

---

### 10. Base H2 fichier & données au repos — **Faible**

**Fichier** : `application.yml:11-26`.

- H2 en base fichier (`jdbc:h2:file:./data/spectra-db`), utilisateur `sa`, **mot de passe vide**.
  La console H2 est bien désactivée (`h2.console.enabled: false`) — bon point.
- Données au repos (base, corpus, archives GED) **non chiffrées** ; le PVC/volume les stocke en clair.

**Recommandations** : mot de passe non trivial même en embarqué, chiffrement des volumes/PVC
(`StorageClass` chiffré) pour les corpus sensibles, sauvegardes chiffrées.

---

### 11. Spécification OpenAPI publique — **Faible**

**Fichier** : `ApiKeyFilter.java:51-52`.

- `/swagger-ui/**` et `/api-docs/**` sont exemptés d'authentification → cartographie complète de l'API
  offerte à un anonyme (facilite l'exploitation des points 1 et 5).

**Recommandation** : ne pas exposer Swagger/OpenAPI en production, ou le protéger par authentification.

---

### 12. Validation de format sur identifiants de chemin — **Faible**

**Fichiers** : `GedController` / `ArticleCommentController` (`@PathVariable String sha256`),
`GedService.writeManifest` (`archiveRoot.resolve(doc.getSha256())`).

- `sha256` (path variable) et `collection` (query) ne sont pas validés par une regex de format.
  L'usage actuel passe par des lookups en base et des filtres de métadonnées ChromaDB (pas de
  concaténation filesystem directe depuis l'entrée brute), donc l'exploitabilité est **faible** — mais
  `archiveRoot.resolve(sha256)` mérite une validation stricte (`^[0-9a-f]{64}$`) en défense en profondeur
  contre tout futur traversal.

**Recommandation** : valider `sha256` (`^[0-9a-fA-F]{64}$`) et restreindre `collection`
(`^[A-Za-z0-9._-]{1,64}$`) au niveau contrôleur.

---

## Plan d'action recommandé (par priorité)

**Court terme (bloquant avant toute exposition réseau)**
1. Exiger une authentification en prod (échec au démarrage si absente) — finding 1.
2. Restreindre l'actuator (`show-details: when-authorized`, port de management séparé) — finding 4.
3. Activer TLS sur l'Ingress + en-têtes de sécurité nginx — finding 7.
4. NetworkPolicy egress stricte sur browserless + token browserless — findings 2, 3.

**Moyen terme**
5. `securityContext` durci + PSS `restricted` + NetworkPolicy default-deny — finding 3.
6. Rate-limiting et quotas sur endpoints coûteux — finding 5.
7. Épingler images par digest + `llmfit` versionné/vérifié + scan d'images (Trivy) + SBOM — finding 6.
8. Lier `actor` à l'identité authentifiée — finding 8.

**Fond**
9. Modèle d'autorisation par rôles (lecture vs admin) — finding 1.
10. Garde-fous d'injection de prompt + revue avant intégration au dataset — finding 9.
11. Chiffrement au repos + mot de passe base — finding 10.
12. Validation de format des identifiants + masquage OpenAPI en prod — findings 11, 12.

---

*Audit réalisé par revue statique du code et de la configuration. Aucune exécution dynamique ni test
d'intrusion n'a été menée ; une validation dynamique en environnement de recette est recommandée pour
confirmer l'exploitabilité des findings 2, 5 et 9.*
