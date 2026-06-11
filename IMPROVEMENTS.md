# Document d'améliorations — Spectra

Ce document recense les améliorations identifiées lors de l'audit et des sessions de débogage.
Chaque item indique l'impact, la difficulté estimée et le fichier concerné.

**Légende difficulté :** 🟢 Facile (< 1h) · 🟡 Modérée (demi-journée) · 🔴 Complexe (plusieurs jours)
**Légende priorité :** 🔥 Critique · ⚠️ Importante · 💡 Nice-to-have

---

## 1. Fiabilité

### A1 — Persistance des tâches et des paires générées 🔴 🔥 ✅ Implémenté

**Problème actuel**
`IngestionService.tasks`, `DatasetGeneratorService.generatedPairs` et `FineTuningService.jobs`
sont des `ConcurrentHashMap` / `CopyOnWriteArrayList` en mémoire. Au moindre redémarrage du
conteneur, toutes les tâches en cours et les paires générées sont perdues sans possibilité de reprise.

**Impact observé**
Lors des tests, chaque redémarrage de conteneur effaçait les paires déjà générées (plusieurs heures
de travail du LLM perdues), obligeant à relancer une génération depuis le début.

**Amélioration proposée**
- Stocker les tâches d'ingestion et les jobs de fine-tuning dans une base embarquée légère (H2,
  SQLite via JDBC, ou fichier JSON persistant).
- Persister les paires générées dans un fichier JSONL de travail (`data/dataset/wip.jsonl`) mis à
  jour chunk par chunk, avec reprise sur le dernier index traité.
- À la reprise, sauter les chunks déjà traités (`chunksProcessed` connu depuis le fichier).

**Fichiers concernés**
`DatasetGeneratorService`, `IngestionService`, `FineTuningService`

> **Implémenté** : `@PostConstruct loadPersistedPairs()` charge `generated_pairs.jsonl` au démarrage ; `persistPairs()` écrit chunk par chunk. `FineTuningService` persiste dans `jobs.json` via `JavaTimeModule`. `IngestionService` restaure les tâches depuis JSONL.

---

### A2 — Déduplication à l'ingestion 🟡 🔥 ✅ Implémenté

**Problème actuel**
Ingérer deux fois le même fichier produit des doublons dans ChromaDB (mêmes vecteurs, même texte,
UUIDs différents). Ces doublons dégradent la qualité du RAG et gonflent artificiellement le dataset.

**Amélioration proposée**
- Calculer un hash SHA-256 du contenu de chaque fichier avant ingestion.
- Stocker les hashes dans un fichier ou base locale.
- Si le hash est connu → ignorer le fichier et loguer un WARN "Fichier déjà ingéré".
- Optionnel : permettre un flag `?force=true` pour forcer la réingestion.

**Fichiers concernés**
`IngestionTaskExecutor`, nouveau `IngestionRegistry`

> **Implémenté** : SHA-256 calculé par fichier temporaire ; hashes persistés dans `ingested_hashes.txt` ; flag `?force=true` disponible sur `POST /api/ingest`.

---

### A3 — Timeout LLM insuffisant sur CPU 🟢 ⚠️ ✅ Implémenté

**Problème actuel**
`OllamaClient.GENERATE_TIMEOUT = Duration.ofMinutes(2)` est trop court pour phi3 sur CPU avec
des chunks de 2048 caractères. Les timeouts représentaient ~30% des chunks lors des tests.

**Amélioration proposée**
- Rendre le timeout configurable via `application.yml` :
  ```yaml
  spectra:
    ollama:
      generate-timeout-seconds: 300   # 5 min par défaut
  ```
- Ajouter `generateTimeoutSeconds` dans `SpectraProperties.OllamaProperties`.
- Injecter la valeur dans `OllamaClient` plutôt que la constante hardcodée.

**Fichiers concernés**
`OllamaClient`, `SpectraProperties`

> **Implémenté** : `spectra.ollama.generate-timeout-seconds: 300` dans `application.yml` ; `OllamaClient` utilise `props.effectiveGenerateTimeoutSeconds()` au lieu de la constante 2 min.

---

### A4 — Format `.doc` (ancien Word) non supporté malgré la déclaration 🟡 ⚠️ ✅ Implémenté

**Problème actuel**
`DocxExtractor.supportedContentTypes()` déclare `application/msword` (extension `.doc`), mais
utilise `XWPFDocument` qui ne lit que le format OOXML (`.docx`). Un fichier `.doc` déclenchera
une exception à l'ouverture.

**Amélioration proposée**
- Créer un `DocExtractor` séparé utilisant `HWPFDocument` (Apache POI HSSF/HWPF).
- Retirer `application/msword` de `DocxExtractor.supportedContentTypes()`.
- Ajouter la dépendance Maven `poi-scratchpad` si absente.

**Fichiers concernés**
`DocxExtractor`, nouveau `DocExtractor`, `pom.xml`

> **Implémenté** : `poi-scratchpad 5.3.0` ajouté dans `pom.xml` ; nouveau `DocExtractor` utilisant `HWPFDocument` + `WordExtractor` ; `DocumentExtractorFactory` résout `.doc` → `application/msword` au lieu de lever une exception.

---

### A5 — Génération dataset : concurrence sur `generatedPairs.clear()` 🟡 🔥 ✅ Implémenté

**Problème actuel**
`DatasetGeneratorService.generate()` commence par `generatedPairs.clear()`. Si deux tâches de
génération tournent simultanément (cas observé en test), chacune efface les paires de l'autre en
cours de route. Le résultat final est 0 paires malgré des heures de traitement.

**Amélioration proposée**
- Remplacer la liste partagée par un résultat propre à chaque tâche (retourner la liste depuis
  `generate()` plutôt que de mutater un état global).
- Ne fusionner dans `generatedPairs` qu'une seule fois la tâche terminée avec succès.
- Ou simplement refuser (`409 Conflict`) si une génération est déjà en cours.

**Fichiers concernés**
`DatasetGeneratorService`

> **Implémenté** : liste locale `localPairs` construite pendant la génération ; fusion atomique dans `generatedPairs` sous `generationLock` uniquement en fin de tâche (lock tenu quelques ms, pas des heures).

---

### A6 — Absence de pagination dans `getAllDocuments()` 🟡 ⚠️ ✅ Implémenté

**Problème actuel**
`ChromaDbClient.getAllDocuments()` charge la collection entière en un seul appel. Sur une collection
de plusieurs milliers de chunks, la réponse ChromaDB dépassera les 16 Mo (buffer actuel) et le
service échouera.

**Amélioration proposée**
- Utiliser la pagination ChromaDB : paramètres `limit` + `offset` dans le body de `GET /collection/{id}/get`.
- Traiter les chunks par pages de 100 dans `DatasetGeneratorService.generate()`.
- Libérer les objets dès leur traitement pour limiter la pression mémoire.

**Fichiers concernés**
`ChromaDbClient`, `DatasetGeneratorService`

> **Implémenté** : boucle `limit=500 / offset` dans `ChromaDbClient.getAllDocuments()` ; arrêt sur page vide ou partielle.

---

## 2. Performance

### B1 — Cache de l'ID de collection ChromaDB 🟢 💡 ✅ Implémenté

**Problème actuel**
`chromaDbClient.getOrCreateCollection(COLLECTION_NAME)` est appelé à chaque requête RAG et à
chaque ingestion, soit un aller-retour HTTP ChromaDB superflu à chaque fois.

**Amélioration proposée**
```java
private volatile String cachedCollectionId;

public String getOrCreateCollection(String name) {
    if (cachedCollectionId == null) {
        synchronized (this) {
            if (cachedCollectionId == null) {
                cachedCollectionId = fetchOrCreate(name);
            }
        }
    }
    return cachedCollectionId;
}
```
- Invalider le cache si ChromaDB retourne 404 (collection supprimée manuellement).

**Fichiers concernés**
`ChromaDbClient`

> **Implémenté** : `ConcurrentHashMap<String, String> collectionIdCache` ; `invalidateCollectionCache()` appelé sur 404 ChromaDB.

---

### B2 — Parallélisation des appels LLM dans la génération de dataset 🔴 💡 ✅ Implémenté

**Problème actuel**
Les 3-4 appels LLM par chunk (Q&A, raffinement, résumé, classification) sont séquentiels. Sur
CPU avec phi3, chaque appel prend 30-60s → 2-4 minutes par chunk.

**Amélioration proposée**
- Lancer les appels résumé et classification en parallèle (ils sont indépendants) via
  `CompletableFuture` ou Virtual Threads.
- L'appel de raffinement reste séquentiel après le Q&A (dépendance logique).
- Gain estimé : réduction de ~30% du temps total par chunk.

**Fichiers concernés**
`DatasetGeneratorService`

> **Implémenté** : résumé, classification et cas négatif lancés en `CompletableFuture` sur `Executors.newVirtualThreadPerTaskExecutor()` ; Q&A + raffinement restent séquentiels sur le thread courant.

---

### B3 — Taille du batch d'embeddings configurable 🟢 💡 ✅ Implémenté

**Problème actuel**
La taille de batch `10` est hardcodée en deux endroits distincts (`IngestionTaskExecutor` ligne 95
et `IngestionService` ligne 127), risque de désynchronisation.

**Amélioration proposée**
- Extraire dans `SpectraProperties` ou en constante partagée dans `EmbeddingService`.
- Permettre la configuration via `spectra.ollama.embedding-batch-size: 10`.

**Fichiers concernés**
`IngestionTaskExecutor`, `IngestionService`, `EmbeddingService`, `SpectraProperties`

> **Implémenté** : `spectra.ollama.embedding-batch-size: 10` dans `application.yml` ; injecté via `@Value` dans `IngestionService` et `IngestionTaskExecutor` ; `OllamaProperties.effectiveEmbeddingBatchSize()` dans `SpectraProperties`.

---

### B4 — Nom de collection `"spectra_documents"` dupliqué dans 3 services 🟢 ⚠️ ✅ Implémenté

**Problème actuel**
La constante `"spectra_documents"` est déclarée séparément dans `IngestionService`,
`DatasetGeneratorService` et `RagService`. Un changement de nom nécessite 3 modifications.

**Amélioration proposée**
- Déclarer `COLLECTION_NAME` dans `ChromaDbClient` (ou une classe `SpectraConstants`).
- Injecter via `@Value("${spectra.chromadb.collection:spectra_documents}")` pour permettre
  plusieurs collections (multi-tenant, multi-domaine).

**Fichiers concernés**
`IngestionService`, `DatasetGeneratorService`, `RagService`, `ChromaDbClient`

> **Implémenté** : `ChromaDbProperties.effectiveCollection()` source unique de vérité ; `IngestionService` reçoit la valeur par `@Value` ; constantes locales supprimées dans tous les services.

---

## 3. Qualité du Dataset

### C1 — Métadonnée `sourceFile` absente dans les paires générées 🟢 ⚠️ ✅ Implémenté

**Problème actuel**
Dans `DatasetGeneratorService.generate()`, les métadonnées récupérées depuis ChromaDB utilisent
la clé `"sourceFile"`, mais à l'ingestion la clé stockée est également `"sourceFile"`.
En pratique, la valeur retournée est souvent `"inconnu"` car le champ n'est pas correctement
propagé depuis le `TextChunk` lors de `addDocuments()`.

**Amélioration proposée**
- Vérifier dans `ChromaDbClient.addDocuments()` que `chunk.sourceFile()` est bien ajouté aux
  métadonnées sous la clé `"sourceFile"`.
- Logger un WARN si `sourceFile` est null ou vide à l'ajout.

**Fichiers concernés**
`ChromaDbClient`, `TextChunk`

> **Implémenté** : `ChunkingService.createChunk()` ajoute `metadata.put("sourceFile", sourceFile)` pour chaque chunk ; la valeur est garantie non-null (fallback "inconnu").

---

### C2 — Parsing JSON fragile dans les réponses LLM 🟡 ⚠️ ✅ Implémenté

**Problème actuel**
`DatasetGeneratorService.extractJson()` extrait le premier `{` et le dernier `}` du texte LLM.
Si phi3 encapsule la réponse JSON dans un bloc Markdown (` ```json ... ``` `), l'extraction
peut échouer silencieusement et retourner `null` pour la paire.

**Amélioration proposée**
- Nettoyer les balises Markdown avant l'extraction : `text.replaceAll("```json|```", "")`.
- Valider que la chaîne extraite est du JSON valide avant de parser (`mapper.readTree()`
  dans un try séparé).
- Si le parsing échoue, loguer la réponse brute en DEBUG pour diagnostic.

**Fichiers concernés**
`DatasetGeneratorService.extractJson()`

> **Implémenté** : `extractJson()` retire les blocs `` ```json ``` `` avant extraction ; garde-fou `null`/blank retourne `"{}"` ; comptage d'accolades inchangé.

---

### C3 — Filtre de qualité RAG : score de distance non appliqué 🟡 ⚠️ ✅ Implémenté

**Problème actuel**
`RagService.query()` injecte dans le contexte tous les chunks retournés par ChromaDB,
quelle que soit leur distance cosinus. Un chunk avec distance > 1.5 (faible pertinence)
peut polluer le contexte et dégrader la réponse.

**Amélioration proposée**
- Ajouter un seuil configurable `spectra.rag.max-distance: 1.2`.
- Filtrer les sources dont `distance > maxDistance` avant de construire le contexte.
- Si tous les chunks sont filtrés → retourner la réponse "aucun document pertinent".

**Fichiers concernés**
`RagService`, `SpectraProperties`

> **Implémenté** : `spectra.rag.max-distance-threshold: 0.8` ; `RagService` filtre les chunks > seuil ; messages différenciés collection vide vs tous chunks hors seuil.

---

### C4 — Équilibrage des paires par source 💡 🟡 ✅ Implémenté

**Problème actuel**
Si un PDF de 300 pages est ingéré et un autre de 5 pages, la quasi-totalité des paires
proviendront du gros document. Le modèle fine-tuné sur-apprendra ce document.

**Amélioration proposée**
- Dans `DatasetGeneratorService`, limiter le nombre de paires par `sourceFile`
  (ex : max 20% des paires totales par source).
- Loguer la distribution par source à la fin de la génération.

**Fichiers concernés**
`DatasetGeneratorService`

> **Implémenté** : `balanceBySource()` plafonne chaque source à 20 % des paires totales après génération complète ; le dépassement est loggé INFO.

---

## 4. Observabilité

### D1 — Métriques applicatives (Micrometer) 🟡 💡 ✅ Implémenté

**Problème actuel**
Aucune métrique n'est exposée. Il est impossible de monitorer :
- le débit d'ingestion (chunks/seconde)
- le taux d'erreur LLM
- la latence RAG

**Amélioration proposée**
Ajouter `spring-boot-starter-actuator` + `micrometer-registry-prometheus` :
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```
Instrumenter avec `Counter`, `Timer`, `Gauge` :
- `spectra.ingestion.chunks.total` (counter)
- `spectra.llm.call.duration` (timer, tag: type=qa/summary/classification)
- `spectra.rag.query.duration` (timer)
- `spectra.dataset.pairs.total` (gauge)

Exposer `/actuator/prometheus` pour Grafana/Prometheus.

**Fichiers concernés**
`pom.xml`, `IngestionTaskExecutor`, `DatasetGeneratorService`, `RagService`

> **Implémenté** : `micrometer-registry-prometheus` ajouté dans pom.xml ; `/actuator/prometheus` exposé dans application.yml. `IngestionTaskExecutor` : `spectra.ingestion.chunks.total` (counter), `spectra.ingestion.files.total` (counter), `spectra.ingestion.file.duration` (timer). `RagService` : `spectra.rag.query.duration` (timer), `spectra.rag.chunks.filtered.total` (counter).

---

### D2 — Retirer les logs de diagnostic heap en production 🟢 💡 ✅ Implémenté

**Problème actuel**
`IngestionTaskExecutor.ingestOne()` logue la mémoire heap avant chaque chunking :
```
Avant chunking 'fichier.pdf': textLen=45231 chars, heap used=82MB / max=2048MB
```
Ces logs ont été ajoutés pour diagnostiquer l'OOM. En production, ils polluent les logs.

**Amélioration proposée**
- Passer ces logs en niveau `DEBUG` (actuellement `INFO`).
- Ou les conditionner à un flag `spectra.debug.heap-logging: false`.

**Fichiers concernés**
`IngestionTaskExecutor` (ligne 84)

> **Implémenté** : `log.info` → `log.debug` dans `IngestionTaskExecutor.ingestOne()`.

---

## 5. Sécurité

### E1 — Authentification API manquante 🟡 🔥 ✅ Implémenté

**Problème actuel**
La spécification initiale prévoit une clé API via header `X-API-Key`, mais Spring Security n'est
pas configuré. N'importe qui sur le réseau peut ingérer des documents, vider la base ou lancer
un fine-tuning.

**Amélioration proposée**
- Ajouter un filtre `OncePerRequestFilter` vérifiant `X-API-Key` sur toutes les routes `/api/**`.
- Configurer la clé via variable d'environnement `SPECTRA_API_KEY`.
- Exclure `/actuator/health` et `/swagger-ui/**` de l'authentification.

**Fichiers concernés**
`AppConfig` ou nouveau `SecurityConfig`, `docker-compose.yml`

> **Implémenté** : nouveau `ApiKeyFilter` (`OncePerRequestFilter`) vérifiant `X-API-Key` sur toutes les routes. Activé uniquement si la variable d'env `SPECTRA_API_KEY` est non vide. Chemins exemptés : `/actuator/**`, `/swagger-ui/**`, `/api-docs/**`.

---

### E2 — Pas de limite de taille sur les uploads 🟢 ⚠️ ✅ Implémenté

**Problème actuel**
Spring Boot accepte par défaut des fichiers multipart jusqu'à 1 Mo. Si cette limite a été élevée
dans la config, un utilisateur malveillant peut uploader un fichier de plusieurs gigaoctets et
saturer le disque ou la mémoire.

**Amélioration proposée**
Fixer une limite explicite dans `application.yml` :
```yaml
spring:
  servlet:
    multipart:
      max-file-size: 100MB
      max-request-size: 500MB
```
Et documenter la limite dans le manuel utilisateur.

**Fichiers concernés**
`application.yml`

> **Implémenté** : `max-file-size: 100MB`, `max-request-size: 500MB` dans `application.yml`.

---

### E3 — Noms de fichiers non sanitizés dans les métadonnées 🟢 ⚠️ ✅ Implémenté

**Problème actuel**
Le nom du fichier uploadé (`MultipartFile.getOriginalFilename()`) est stocké tel quel dans les
métadonnées ChromaDB et dans les logs. Un nom de fichier comme `../../../etc/passwd` ou
`; rm -rf /` peut causer des problèmes si le nom est réutilisé dans une commande système.

**Amélioration proposée**
- Sanitizer le nom avant stockage : conserver uniquement `[a-zA-Z0-9._-]`, max 255 caractères.
- Ne jamais utiliser le nom fourni par l'utilisateur dans une commande shell.

**Fichiers concernés**
`IngestionService.submit()`, `IngestionTaskExecutor.ingestOne()`

> **Implémenté** : `IngestionService.sanitizeFileName()` remplace tout caractère hors `[a-zA-Z0-9._-]` par `_`, tronque à 255 caractères ; appliqué lors de la construction de `toProcessNames`.

---

## 6. Fonctionnalités Manquantes

### F1 — Suppression et listing des documents ingérés 🟡 ⚠️ ✅ Implémenté

**Problème actuel**
Il n'existe aucun moyen de lister les documents dans ChromaDB ni de supprimer un document ingéré
par erreur. La seule option est de vider toute la base (`docker compose down -v`).

**Amélioration proposée**
Nouveaux endpoints :
```
GET  /api/documents          → liste des fichiers sources avec leur nombre de chunks
DELETE /api/documents/{sourceFile} → supprime tous les chunks d'un fichier (ChromaDB filter delete)
```

**Fichiers concernés**
`ChromaDbClient` (nouvelle méthode `deleteBySource()`), nouveau `DocumentController`

> **Implémenté** : `ChromaDbClient.listSources()` agrège les métadonnées par `sourceFile` ; `ChromaDbClient.deleteBySource()` appelle l'API ChromaDB `delete` avec filtre `where` ; `DocumentController` expose `GET /api/documents` et `DELETE /api/documents/{sourceFile}`.

---

### F2 — Reprise de génération de dataset interrompue 🔴 💡 ✅ Implémenté

**Problème actuel**
Si la génération de dataset s'interrompt (timeout, redémarrage), tout le travail déjà effectué
est perdu et il faut recommencer de zéro.

**Amélioration proposée**
- Écrire chaque paire dans `data/dataset/wip.jsonl` dès sa génération (flush immédiat).
- Maintenir un fichier `data/dataset/progress.json` : `{"lastProcessedIndex": 42, "taskId": "..."}`.
- Au démarrage d'une nouvelle tâche, détecter si un WIP existe et proposer la reprise.

**Fichiers concernés**
`DatasetGeneratorService`

> **Implémenté** : écriture incrémentale dans `generation_wip.jsonl` après chaque chunk ; `generation_progress.json` stocke `lastProcessedIndex` et `totalChunks`. Au démarrage d'une nouvelle génération, si le progress existe avec le même `totalChunks`, reprise automatique depuis le dernier index. Fichiers WIP supprimés après complétion.

---

### F3 — Support du format Avro 🔴 💡 ✅ Implémenté

**Problème actuel**
La spécification initiale mentionne les "messages JSON résultant d'un décodage Avro", mais aucun
extracteur Avro n'est implémenté. Des fichiers `.avro` sont rejetés avec "extension non supportée".

**Amélioration proposée**
- Ajouter la dépendance `org.apache.avro:avro`.
- Créer `AvroExtractor` : lire le schéma embarqué, désérialiser chaque enregistrement, déléguer
  à `JsonExtractor` le texte JSON produit.
- Ajouter `.avro` dans `DocumentExtractorFactory.resolveContentType()`.

**Fichiers concernés**
Nouveau `AvroExtractor`, `DocumentExtractorFactory`, `pom.xml`

> **Implémenté** : `org.apache.avro:avro:1.12.0` ajouté ; `AvroExtractor` utilise `DataFileStream` + `GenericDatumReader` pour lire le schéma embarqué et sérialiser chaque enregistrement en texte JSON ; `.avro` → `application/avro` dans la factory.

---

### F4 — Collections multiples (multi-domaine) 🟡 💡 ✅ Implémenté

**Problème actuel**
Tous les documents sont ingérés dans la collection `"spectra_documents"`. Il est impossible de
séparer les procédures d'exploitation des messages d'incident dans des collections distinctes,
ce qui limiterait la contamination sémantique lors des requêtes RAG.

**Amélioration proposée**
- Ajouter un paramètre `collectionName` optionnel dans `POST /api/ingest`.
- Permettre de cibler une collection précise dans `POST /api/query`.
- Exposer `GET /api/collections` pour lister les collections disponibles.

**Fichiers concernés**
`IngestController`, `IngestionService`, `QueryController`, `RagService`

> **Implémenté** : `POST /api/ingest` accepte `?collection=` ; `POST /api/query` (via `QueryRequest.collection`) et `RagService.doQuery()` utilisent la collection demandée si fournie, sinon la collection par défaut ; `GET /api/documents` et `DELETE /api/documents/{f}` acceptent aussi `?collection=`.

---

## 7. Infrastructure & Déploiement

### G1 — Fichiers `.gitattributes` pour LF obligatoire sur les scripts 🟢 🔥 ✅ Implémenté

**Problème actuel**
`scripts/train.sh` est checké avec des fins de ligne CRLF sous Windows (Git par défaut).
Le conteneur Linux rejette alors le shebang `#!/usr/bin/env bash\r`. Le fix manuel
`sed -i 's/\r//' scripts/train.sh` doit être répété après chaque checkout.

**Amélioration proposée**
Créer `.gitattributes` à la racine :
```
*.sh    text eol=lf
*.py    text eol=lf
*.bat   text eol=crlf
*.md    text eol=lf
*.java  text eol=lf
*.yml   text eol=lf
```
Cela force Git à normaliser les fins de ligne au checkout, quelle que soit la plateforme.

**Fichiers concernés**
Nouveau `.gitattributes`

> **Implémenté** : `.gitattributes` créé avec `eol=lf` sur `.sh`, `.py`, `.java`, `.yml`, `.json`, `.xml`, `.md`, `Dockerfile` ; `eol=crlf` sur `.bat`/`.cmd` ; binaire sur `.pdf`, `.docx`, `.doc`, `.avro`, `.gguf`, `.safetensors`.

---

### G2 — `.gitignore` pour les données et modèles 🟢 ⚠️ ✅ Implémenté

**Problème actuel**
Les dossiers `data/documents/`, `data/dataset/`, `data/fine-tuning/` et les modèles Ollama
peuvent être committés accidentellement (PDF confidentiels, fichiers GGUF de plusieurs Go).

**Amélioration proposée**
Compléter ou créer `.gitignore` :
```
data/documents/
data/dataset/
data/fine-tuning/
*.jsonl
*.gguf
*.safetensors
__pycache__/
*.pyc
```

**Fichiers concernés**
`.gitignore`

> **Implémenté** : ajout de `*.gguf`, `*.safetensors`, `*.bin`, `__pycache__/`, `*.pyc`/`.pyo` au `.gitignore` (le dossier `data/` était déjà exclu).

---

### G3 — Healthcheck avec vérification fonctionnelle 🟢 💡 ✅ Implémenté

**Problème actuel**
Le healthcheck Docker teste uniquement `/actuator/health` (Spring Boot UP/DOWN). Il ne détecte
pas qu'Ollama ou ChromaDB est tombé après le démarrage.

**Amélioration proposée**
- Ajouter un endpoint `/api/health/deep` qui appelle `ollamaClient.checkHealth()` et
  `chromaDbClient.checkHealth()` et retourne `503` si l'un est indisponible.
- Utiliser ce nouvel endpoint dans le healthcheck Docker pour déclencher un redémarrage
  automatique si les dépendances sont perdues.

**Fichiers concernés**
`StatusController`, `docker-compose.yml`

> **Implémenté** : `GET /api/status/deep` dans `StatusController` appelle `checkHealth()` sur Ollama et ChromaDB ; retourne HTTP 200 si tous disponibles, HTTP 503 sinon (utilisable comme `healthcheck` Docker).

---

## Résumé priorisé

| # | Amélioration | Priorité | Difficulté | État |
|---|---|---|---|---|
| A1 | Persistance des tâches/paires | 🔥 | 🔴 | ✅ |
| A2 | Déduplication à l'ingestion | 🔥 | 🟡 | ✅ |
| A3 | Timeout LLM configurable | ⚠️ | 🟢 | ✅ |
| A4 | Support `.doc` (HWPFDocument) | ⚠️ | 🟡 | ✅ |
| A5 | Concurrence `generatedPairs.clear()` | 🔥 | 🟡 | ✅ |
| A6 | Pagination `getAllDocuments()` | ⚠️ | 🟡 | ✅ |
| B1 | Cache ID collection | 💡 | 🟢 | ✅ |
| B2 | Parallélisation appels LLM | 💡 | 🔴 | ✅ |
| B3 | Batch embeddings configurable | 💡 | 🟢 | ✅ |
| B4 | Constante collection dupliquée | ⚠️ | 🟢 | ✅ |
| C2 | Parsing JSON fragile (Markdown) | ⚠️ | 🟢 | ✅ |
| C3 | Filtre distance RAG | ⚠️ | 🟡 | ✅ |
| C1 | `sourceFile` dans les métadonnées ChromaDB | ⚠️ | 🟢 | ✅ |
| C4 | Équilibrage paires par source | 💡 | 🟡 | ✅ |
| D1 | Métriques Micrometer | 💡 | 🟡 | ✅ |
| D2 | Logs heap en DEBUG | 💡 | 🟢 | ✅ |
| E1 | Authentification API | 🔥 | 🟡 | ✅ |
| E2 | Limite taille upload | ⚠️ | 🟢 | ✅ |
| E3 | Sanitisation noms de fichiers | ⚠️ | 🟢 | ✅ |
| F1 | Listing/suppression documents | ⚠️ | 🟡 | ✅ |
| F2 | Reprise génération interrompue | 💡 | 🔴 | ✅ |
| F3 | Support Avro | 💡 | 🔴 | ✅ |
| F4 | Collections multiples | 💡 | 🟡 | ✅ |
| G1 | `.gitattributes` fins de ligne | 🔥 | 🟢 | ✅ |
| G2 | `.gitignore` données | ⚠️ | 🟢 | ✅ |
| G3 | Healthcheck fonctionnel | 💡 | 🟢 | ✅ |
| H1 | Alignement DPO | ⚠️ | 🔴 | ✅ |
| H2 | Évaluation LLM-as-a-judge | 💡 | 🟡 | ✅ |
| H3 | Multipacking | 💡 | 🟡 | ✅ |
| H4 | Recettes YAML | 💡 | 🟢 | ✅ |
| I1 | Re-ranking (Cross-Encoders) | 🔥 | 🟡 | ✅ |
| I2 | Hybrid Search (Vector + FTS) | ⚠️ | 🟡 | ✅ |
| I3 | Layout-Aware Parsing (Docling) | ⚠️ | 🔴 | ✅ |
| I4 | Agentic RAG (Multi-step) | 💡 | 🔴 | ✅ |

---

## 8. Inspirations du Marché (Améliorations futures)

Ces idées sont issues de l'analyse du framework Axolotl pour enrichir Spectra tout en gardant son focus "métier".

### H1 — Alignement par DPO (Direct Preference Optimization) 🔴 ⚠️ ✅ Implémenté

**Concept Axolotl** : Axolotl supporte nativement le DPO pour affiner le comportement du modèle via des préférences (choisi vs rejeté).

**Adaptation pour Spectra** :
Lors de la génération du dataset, demander au LLM de produire pour chaque question :
1. Une réponse **Correcte** (basée sur le chunk).
2. Une réponse **Hallucinée ou Erronée** (mais plausible).
Entraîner ensuite avec une perte DPO pour que le modèle apprenne explicitement à rejeter les erreurs courantes du domaine.

> **Implémenté** : `DpoPair` record + `DpoGenerationService` (génère les réponses rejetées via le LLM avec un prompt de hallucination). `POST /api/dataset/dpo/generate` → tâche async, polling via `GET /api/dataset/dpo/generate/{taskId}`. `FineTuningService` exporte un dataset DPO si `dpoEnabled=true`. `train_host.py` et `train.sh` : `--dpo` flag → `DPOTrainer` (trl) avec `DPOConfig` ; fallback SFT si trl < 0.4. Checkbox "Alignement DPO" dans `FineTuning.tsx`.

---

### H2 — Évaluation automatique (LLM-as-a-judge) 🟡 💡 ✅ Implémenté

**Concept Axolotl** : Intégration de benchmarks et d'évaluations post-entraînement.

**Adaptation pour Spectra** :
Ajouter une étape "Évaluation" après le fine-tuning :
1. Isoler 5% du dataset comme set de test.
2. Faire répondre le modèle de base et le modèle fine-tuné sur ces questions.
3. Utiliser un LLM plus puissant (ex: GPT-4 ou un gros modèle local) pour noter les réponses de 1 à 10.
4. Afficher un rapport comparatif de progression dans l'onglet "Model Comparison".

> **Implémenté** : `EvaluationService` échantillonne 5 % du dataset (min 5, max 50 paires), interroge le modèle actif, puis l'utilise comme juge (note 1–10 + justification JSON). Résultats persistés dans `evaluations.json`. `EvaluationController` : `POST /api/evaluation`, `GET /api/evaluation`, `GET /api/evaluation/{evalId}`. Page `Comparison.tsx` entièrement réécrite : liste des évaluations, score global, barres par catégorie, détail question/référence/réponse/justification.

---

### H3 — Multipacking (Efficacité d'entraînement) 🟡 💡 ✅ Implémenté

**Concept Axolotl** : Concaténer plusieurs exemples courts dans une seule séquence de 2048 tokens pour minimiser le padding.

**Adaptation pour Spectra** :
Même sur CPU, le multipacking permettrait d'accélérer l'entraînement en traitant plus de données par itération. Modifier `train_host.py` pour implémenter un `DataCollator` qui packe les séquences.

> **Implémenté** : `PackedDataset` dans `train_host.py` (greedy bin-packing, affiche le ratio d'utilisation et l'économie de padding). `--packing` flag dans `train_host.py` et `train.sh` (`$8`). `SFTTrainer` avec `packing=True` dans `train.sh` (GPU). Checkbox "Multipacking" dans `FineTuning.tsx` + champ `packingEnabled` dans `FineTuningRequest`.

---

### H4 — Recettes d'entraînement (YAML Recipes) 🟢 💡 ✅ Implémenté

**Concept Axolotl** : Toute la configuration est centralisée dans un fichier YAML réutilisable.

**Adaptation pour Spectra** :
Permettre d'exporter et d'importer la configuration d'un job de fine-tuning sous forme de fichier YAML. Cela permettrait aux utilisateurs de partager des "recettes" optimales pour certains types de documents (ex: "Recette pour procédures techniques" vs "Recette pour documents juridiques").

> **Implémenté** : `RecipeController` — `GET /api/fine-tuning/recipes` (liste), `GET /api/fine-tuning/recipes/{name}` (JSON pour pré-remplissage), `POST /api/fine-tuning/recipe/export` (téléchargement YAML). 3 recettes prédéfinies : `cpu-rapide.yml`, `gpu-qualite.yml`, `dpo-alignement.yml`. Sélecteur de recettes dans `FineTuning.tsx` avec boutons preset + bouton "Exporter". Sérialisation SnakeYAML (déjà sur le classpath Spring Boot).

---

## 9. Améliorations Stratégiques (Market-driven)

### I1 — Re-ranking post-retrieval (Cross-Encoders) 🟡 🔥 ✅ Implémenté

**Inspiration** : Dify, RAGFlow.
La recherche vectorielle seule est parfois imprécise. L'ajout d'une étape de re-ranking avec un modèle Cross-Encoder (ex: BGE-Reranker) permet de ré-évaluer la pertinence des top-N documents et d'améliorer drastiquement la précision du RAG.

> **Implémenté** : microservice Python `reranker/` (FastAPI + `sentence-transformers` CrossEncoder, modèle configurable via `RERANKER_MODEL`, défaut `cross-encoder/ms-marco-MiniLM-L-6-v2`). `RerankerClient` interface + `CrossEncoderRerankerClient` HTTP (`@ConditionalOnProperty spectra.reranker.enabled=true`). `RagService` récupère `topCandidates` (défaut 20) chunks de ChromaDB, les re-classe via le service, puis ne garde que `maxContextChunks` (défaut 5) pour le LLM. `QueryResponse.Source` expose `rerankScore` ; `QueryResponse` expose `rerankApplied`. `QueryRequest` accepte `topCandidates`. Activé via `SPECTRA_RERANKER_ENABLED=true` (désactivé par défaut). Service `reranker` ajouté dans `docker-compose.yml` (port 8002, healthcheck).

### I2 — Hybrid Search (Vecteurs + Plein texte) 🟡 ⚠️ ✅ Implémenté

**Inspiration** : Qdrant, Milvus.
Combiner la recherche sémantique (vecteurs) avec une recherche par mots-clés traditionnelle (BM25/FTS) permet de ne pas rater des termes techniques précis ou des numéros de procédures que l'embedding pourrait diluer.

> **Implémenté** : `BM25Index` — BM25Okapi pur Java en mémoire, thread-safe (ReadWriteLock), tokeniseur adapté au français (accents). `FtsService` — gère un index BM25 par collection ChromaDB ; rebuild asynchrone depuis ChromaDB au démarrage ; mis à jour à chaque ingestion (`IngestionTaskExecutor`) et suppression (`DocumentController`). `HybridSearchService` — lance en parallèle la recherche vectorielle (ChromaDB) et BM25 (FtsService), fusionne via Reciprocal Rank Fusion (RRF, k=60, poids BM25 configurable). Activé par `SPECTRA_HYBRID_SEARCH_ENABLED=true` (désactivé par défaut). Compatible avec le re-ranking I1 (s'enchaîne après la fusion). `QueryResponse` expose `hybridSearchApplied` (boolean) et `Source.bm25Score` (Float). Config : `spectra.hybrid-search.{enabled, top-bm25, bm25-weight}`.

### I3 — Layout-Aware Parsing (Docling / DeepDoc) 🔴 ⚠️ ✅ Implémenté

**Inspiration** : RAGFlow.
Remplacer le parsing PDF textuel par un parsing conscient de la mise en page (layout) pour extraire proprement les tableaux et les relations hiérarchiques dans les documents techniques autoroutiers.

> **Implémenté** : microservice Python `docparser/` (FastAPI + `pymupdf4llm`) qui transforme les PDF en Markdown structuré (titres `#`/`##`, tableaux `| col |`, listes). Upgrade optionnel vers Docling (IBM) via `USE_DOCLING=true` (modèles IA, ~500 Mo supplémentaires). `LayoutParserClient` — client HTTP multipart. `LayoutAwarePdfExtractor` — remplace `PdfExtractor` quand `spectra.layout-parser.enabled=true` ; fallback automatique vers PDFBox si le service est hors ligne. `PdfExtractor` rendu conditionnel (`@ConditionalOnProperty`) pour éviter les conflits de factory. Métadonnée `parser: pymupdf4llm|docling` ajoutée à chaque chunk. Service `docparser` dans `docker-compose.yml` (port 8003, healthcheck). Activé via `SPECTRA_LAYOUT_PARSER_ENABLED=true` (désactivé par défaut).

### I4 — Agentic RAG (Boucle de raisonnement) 🔴 💡 ✅ Implémenté

**Inspiration** : LangGraph, Dify agents.
Permettre au système de décider s'il a besoin de plus de contexte ou de consulter une source externe avant de répondre, via une boucle de type ReAct (Reasoning and Acting).

> **Implémenté** : `AgenticRagService` — boucle ReAct (THOUGHT → ACTION : SEARCH|ANSWER) activée via `SPECTRA_AGENTIC_RAG_ENABLED=true`. Le LLM reçoit un prompt structuré et décide à chaque itération s'il émet `ACTION: SEARCH` (requête complémentaire → nouveau retrieval, déduplication par texte) ou `ACTION: ANSWER` (extraction de la réponse finale). La boucle est bornée par `max-iterations` (défaut 3) ; fallback sur génération directe si le budget est épuisé. Compatible I1 (re-ranking) et I2 (hybrid search) : le pipeline d'enrichissement initial est exécuté avant d'entrer dans la boucle. `RagService` injecte `Optional<AgenticRagService>` et délègue lorsque le bean est présent. `QueryResponse` enrichi : champs `agenticApplied` (boolean) et `agenticIterations` (int). Config : `spectra.agentic-rag.{enabled, max-iterations, initial-top-k}` + variables d'env `SPECTRA_AGENTIC_*`. Aucun impact sur les déploiements existants (désactivé par défaut).
