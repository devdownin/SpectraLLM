# Audit — Pipeline d'ingestion & GED

> Audit du 2026-07-17. Périmètre : `IngestionService`, `IngestionTaskExecutor`, `UrlIngestionService`,
> `UrlFetcherService`, `KafkaIngestionListener`, `ChunkingService`, `TextCleanerService`, extracteurs,
> `ChromaDbClient`, `FtsService`, `EmbeddingService`/clients, `GedService`, `RetentionPolicyService`,
> `ConsistencyReconciliationService`, contrôleurs (`IngestController`, `GedController`, `DocumentController`),
> entités/repositories associés et configuration (`application.yml`).
>
> Constat général : le pipeline est globalement bien conçu (dédup streaming, garde-fous ZIP-bomb,
> circuit breakers, réconciliation périodique, progression live). Les défauts trouvés se concentrent
> sur la **cohérence entre les trois magasins** (DB relationnelle / ChromaDB / BM25) et sur les
> **chemins de suppression et de ré-ingestion**.

---

## 1. Bugs

### B1 — Ré-ingestion forcée (`force=true`) : chunks dupliqués dans les index — **critique**

`IngestionService.submit(files, force=true)` réindexe le contenu sans purger les anciens chunks.
`recordIngestion()` détecte le doublon (`repository.existsById(hash)`) et appelle
`gedService.incrementVersion()` (R4), mais **rien ne supprime l'ancienne version** dans ChromaDB
ni dans BM25 : les nouveaux chunks (nouveaux UUID, même `sourceFile`) s'ajoutent aux anciens.

- Effet : chaque `force=true` double les chunks du document dans le retrieval (mêmes passages
  retournés N fois, pollution du top-K, `chunksCreated` en GED devient faux).
- Le flux Kafka fait le bon geste (`upsertFromStream` = delete-then-index) ; le chemin upload ne
  l'a pas.
- Fix suggéré : avant l'indexation d'un fichier déjà présent en base (ou dont le nom de source
  existe déjà), appeler `chromaDbClient.deleteBySource()` + `ftsService.removeBySource()` —
  exactement comme `purgeSource()` du chemin streaming.

Références : `IngestionService.submit()` / `recordIngestion()` (backend/src/main/java/fr/spectra/service/IngestionService.java:382),
`GedService.incrementVersion()` (GedService.java:122).

### B2 — Purge de rétention : chunks orphelins à vie dans ChromaDB/BM25 — **critique**

`RetentionPolicyService.autoPurge()` supprime les entrées ARCHIVED directement via
`fileRepo.delete(doc)` (RetentionPolicyService.java:69-72), **sans** passer par
`GedService.deleteDocument()` :

- les chunks restent indexés dans ChromaDB et BM25 → toujours servis par le RAG alors que le
  document est censé être purgé ;
- les lignes `ged_audit_log` et `document_model_links` du document deviennent orphelines ;
- comme la ligne DB (et donc le hash) disparaît, une ré-ingestion du même fichier passe la dédup
  et **duplique** les chunks restants (même effet que B1).

Fix : `autoPurge()` doit appeler `gedService.deleteDocument(sha256, "retention-policy")`.

### B3 — Identité des chunks par `fileName` : collisions entre documents — **majeur**

La dédup GED est par **SHA-256**, mais l'identité des chunks dans les index est le **nom de
fichier** (`sourceFile`). Deux documents différents portant le même nom (`rapport.pdf` v1 et v2,
ou deux fichiers homonymes de dossiers différents) :

- coexistent en GED (2 lignes, 2 hash) mais partagent le même `sourceFile` dans ChromaDB/BM25 ;
- `GedService.deleteDocument(shaA)` supprime par `deleteBySource(fileName)` → **efface aussi les
  chunks du document B**, dont la fiche GED continue pourtant d'annoncer `chunksCreated > 0`.

Fix suggéré : ajouter `sha256` aux métadonnées de chaque chunk à l'ingestion et supprimer par
`where {sha256: …}` ; garder `sourceFile` pour l'affichage. (Nécessite une migration douce :
fallback sur `sourceFile` pour les chunks existants.)

Références : `GedService.deleteDocument()` (GedService.java:283-308), `ChunkingService.createChunk()`.

### B4 — `DELETE /api/documents/{sourceFile}` désynchronise la GED — **majeur**

`DocumentController.deleteDocument()` purge ChromaDB + BM25 mais **ne touche pas** la ligne
`ingested_files` (DocumentController.java:58-72). Conséquences :

- la GED annonce un document ingéré avec N chunks alors qu'il n'est plus interrogeable ;
- la dédup SHA-256 **bloque toute ré-ingestion** du même fichier (hors `force`) → le document est
  définitivement invisible du RAG tant qu'on ne force pas… ce qui déclenche B1.

Il existe donc deux chemins de suppression aux sémantiques divergentes (`/api/ged/documents/{sha}`
= complet ; `/api/documents/{sourceFile}` = index seulement). Fix : faire du endpoint documents un
alias de la suppression GED (résolution `sourceFile → sha256`), ou au minimum supprimer/marquer la
ligne GED correspondante.

### B5 — Échecs silencieusement convertis en succès dans le suivi de tâche — **majeur**

Trois cas où l'utilisateur voit `COMPLETED` alors que des fichiers ont échoué :

1. `IngestionService.submit()` : une exception de `copyAndHash()` (IngestionService.java:185-188)
   écarte le fichier **sans le signaler** ; si tous les fichiers échouent à la préparation, la
   tâche est marquée `completed(0)` avec le log « déjà ingérés » — faux.
2. `IngestionTaskExecutor.execute()` : une exception d'`ingestOne()` (format non supporté,
   extraction corrompue…) est loggée puis `chunks=0` (IngestionTaskExecutor.java:167-170) ; la
   tâche finit `COMPLETED` sans trace de l'erreur ni du fichier en échec.
3. `UrlIngestionService.submit()` : si au moins un chunk a été indexé, `lastError` est jeté et la
   tâche est `COMPLETED` (UrlIngestionService.java:70-75) — les URLs en échec sont invisibles.

Fix : accumuler les erreurs par fichier dans `IngestionTask` (ex. champ `fileErrors:
Map<String,String>`) et exposer un statut `COMPLETED_WITH_ERRORS` ou au minimum renseigner
`error` en fin de tâche.

### B6 — Échec partiel d'embedding : document durablement amputé, sans voie de réparation — **majeur**

`embedAndStore()` s'interrompt au premier lot en échec mais conserve les chunks déjà indexés
(choix assumé), et `recordIngestion()` enregistre le hash avec le compte partiel. Ensuite :

- la dédup SHA-256 **empêche** de ré-ingérer le document pour compléter ;
- la seule échappatoire est `force=true`… qui duplique les chunks déjà présents (B1).

Fix : soit purger la source et ne pas enregistrer le hash quand `embedded < chunks.size()`
(atomicité par document), soit stocker un flag `partial` en GED et faire de la ré-ingestion
d'un document `partial` un upsert (purge + réindex).

Références : IngestionTaskExecutor.java:290-310, IngestionService.recordIngestion().

### B7 — L'ingestion par URL contourne la limite de concurrence et la réservation in-flight — **moyen**

`UrlIngestionService.submit()` lance un thread virtuel par requête et appelle
`IngestionService.ingest()` → `executor.ingestOne()` **sans** passer par le sémaphore
`concurrencySemaphore` (réservé au chemin `execute()`), et **sans** réservation
`inFlightHashes` :

- N requêtes `/api/ingest/url` simultanées = N pipelines d'extraction/embedding en parallèle,
  hors de tout plafond (`concurrent-ingestions=1` par défaut est ignoré) → pression mémoire/CPU ;
- deux soumissions concurrentes de la même URL passent toutes deux `existsById(hash)` avant que
  la ligne existe → chunks doublés (la fenêtre que `tryClaimHash()` ferme côté upload).

Fix : router les URLs vers le même `executor.execute()` (elles bénéficieraient au passage de la
progression live et de l'annulation), ou a minima acquérir le sémaphore et réutiliser
`tryClaimHash()` dans `ingest()`.

### B8 — Limite mémoire non appliquée aux uploads directs — **moyen**

`maxUncompressedBytes` (auto-calculée : 8–512 Mo selon heap/concurrence) est contrôlée pour les
entrées ZIP (`LimitedInputStream`) et pour `ingestLocalFiles()` (`Files.size` check), mais **pas**
pour un fichier uploadé directement ni pour une URL : `ingestOne()` ouvre le fichier temp sans
borne et la plupart des extracteurs font `readAllBytes()` (ex. PdfExtractor.java:34).

Avec `max-file-size: 50MB` (multipart) et l'amplification ×6 documentée dans `IngestionLimits`,
un petit heap (limite auto 8 Mo) peut être saturé par un upload légitime de 50 Mo. L'OOM est
attrapé (`catch OutOfMemoryError`) mais un OOM n'est jamais anodin pour le reste de la JVM.

Fix : dans `ingestOne()`, vérifier `Files.size(tempFile) > maxEntryUncompressedBytes` (comme le
fait déjà le chemin batch) et/ou envelopper le flux dans `LimitedInputStream`.

### B9 — Rétention : auto-archivage/purge basés sur `ingestedAt`, jamais rafraîchi — **moyen**

- `incrementVersion()` repasse le document en `INGESTED` mais **ne met pas à jour `ingestedAt`** :
  un document ancien ré-ingéré (nouvelle version) sera auto-archivé la nuit même par
  `autoArchive()` (cutoff sur `ingestedAt`).
- `autoPurge()` purge les ARCHIVED selon `ingestedAt`, pas selon la date d'archivage : un document
  archivé hier mais ingéré il y a un an est purgé immédiatement — contraire à l'intention
  « ARCHIVED vieux de M jours ».

Fix : rafraîchir `ingestedAt` (ou ajouter `lastVersionAt`) dans `incrementVersion()`, et tracer
`archivedAt` pour la purge.

### B10 — FTS : le rebuild au démarrage peut écraser des chunks fraîchement ingérés — **moyen**

`rebuildCollection()` charge l'index depuis le disque (ou ChromaDB) puis fait
`indices.put(collectionName, index)` (FtsService.java:121, 157). Une ingestion démarrée pendant
le rebuild a pu créer/alimenter l'index en mémoire via `indexChunks()` : le `put` **écrase** ces
ajouts. Sur le chemin disque, l'index `.bin` peut de plus être en retard de 5 s (flush différé)
ou davantage : les chunks concernés disparaissent de BM25 jusqu'au prochain rebuild.

Fix : fusionner au lieu de remplacer (`indices.merge`), ou re-jouer les ajouts postérieurs au
début du rebuild ; à défaut, faire le `put` seulement si `indices.get(name)` est encore absent.

### B11 — Score de qualité plafonné à 0,86 : l'auto-qualification peut être inatteignable — **mineur**

`GedService.computeQualityScore()` documente un score « 0.0–1.0 » mais son maximum réel est
`0.7 + 0.2×0.3 + 0.1 = 0.86` (GedService.java:218-229). Un
`spectra.ged.auto-qualify-threshold` réglé à 0.9 ne qualifiera **jamais** rien, sans erreur ni
avertissement.

Fix : renormaliser (`chunkScore*0.7 + formatBonus + bonus`, avec formatBonus déjà ≤ 0.2), ou
documenter le plafond effectif.

### B12 — Réservation in-flight : TTL de 15 min plus court que certaines ingestions — **mineur**

`INFLIGHT_TTL = 15 min` : une ingestion légitime plus longue (gros ZIP, embedder lent) voit sa
réservation « reprise » par un upload concurrent du même contenu (`tryClaimHash` remplace la
réservation périmée) → double indexation. Fix : rafraîchir la réservation depuis le callback de
progression, ou dimensionner le TTL sur la durée max observée.

### B13 — Divers (mineurs)

- **`recordIngestion` fige `defaultCollection`** : `ingest(fileName, in, collectionId)` indexe dans
  `collectionId` mais enregistre systématiquement `defaultCollection` en GED
  (IngestionService.java:437). Correct aujourd'hui (seul appelant = URL/collection défaut), mais
  toute réutilisation avec une autre collection casserait la suppression synchronisée.
- **Filtre tag non échappé** : `findFiltered` échappe `%`/`_` pour `q` mais pas pour `tag`
  (GedService.java:257-261) — un tag contenant `%` filtre trop large.
- **`retryRebuildIfEmpty` boucle indéfiniment** sur une collection légitimement vide : un appel
  ChromaDB + un log par minute, pour toujours (FtsService.java:89-100). Prévoir un marqueur
  « rebuild réussi mais vide ».
- **Annulation à gros grain** : le point de contrôle d'annulation est entre fichiers
  (IngestionTaskExecutor.java:148-154) — un ZIP de 10 000 entrées ou un très gros PDF n'est pas
  annulable. Vérifier aussi l'annulation dans le callback `progress` (entre lots d'embeddings).
- **`Lifecycle` TRAINED inaccessible en masse** : rien dans le pipeline ne pose TRAINED
  automatiquement lors d'un fine-tuning utilisant le document (les liens R1 existent, la
  transition n'est pas appelée) — à vérifier côté FineTuningService si c'est voulu.

---

## 2. Optimisations

### O1 — ChromaDB `deleteBySource` en un seul aller-retour
`deleteBySource()` fait GET (jusqu'à 1 M d'ids, timeout 30 s) puis DELETE par liste d'ids avec
`TIMEOUT_DEFAULT` = 10 s — le delete d'une très grosse source peut expirer alors que le GET a
réussi. ChromaDB accepte `POST /delete` avec un filtre `where` directement : un seul appel, payload
constant. Garder le GET seulement si le compte exact est nécessaire (ou utiliser `count()` avant/après).

### O2 — Suppressions GED en masse côté SQL
`deleteDocumentDb()` charge toutes les entités (`findBy…` puis `deleteAll`) : 2N requêtes. Ajouter
`deleteByDocumentSha256(String)` (`@Modifying @Query`) sur `AuditLogRepository` et
`DocumentModelLinkRepository` (les index existent déjà).

### O3 — Hex encoding des hash
`copyAndHash`/`sha256` formatent chaque octet via `String.format("%02x")` (64 appels + regex par
fichier). `java.util.HexFormat.of().formatHex(digest.digest())` est ~50× plus rapide et plus lisible.

### O4 — Lot d'embedding par défaut
`embedding-batch-size: 10` est petit pour un endpoint `/v1/embeddings` batch : chaque fichier de
500 chunks = 50 allers-retours HTTP + 50 POST ChromaDB. 32–64 réduit nettement la latence
d'ingestion (à calibrer selon le contexte du modèle d'embedding). En complément, un
`@Retry` sur `ChromaDbClient.addDocuments()` (comme sur `query`) éviterait qu'une erreur réseau
transitoire ampute un document (voir B6).

### O5 — `/api/documents` (listSources) charge toute la collection
`listSources()` pagine mais accumule ids+documents+metadatas de **toute** la collection en mémoire
pour ne compter que les sources. Ne demander que `metadatas` (retirer `documents` de l'include)
et agréger page par page sans accumuler ; ou tenir ce compte en DB (la GED l'a déjà :
`fileName → chunksCreated`).

### O6 — Flush FTS pendant les grosses ingestions
`flushDirtyIndices` sérialise l'index **complet** toutes les 5 s tant que l'ingestion marque la
collection dirty — sur un gros index c'est un coût I/O récurrent. Pistes : flush à la fin de
tâche + timer plus espacé (30 s), ou sérialisation incrémentale.

### O7 — Formats manquants faciles
`resolveContentType()` ne route ni `.md`, ni `.csv`, ni `.xlsx` (la javadoc d'`IngestionService`
mentionne pourtant XLSX). `.md` et `.csv` peuvent être mappés sur `text/plain` en une ligne ;
XLSX nécessiterait POI (déjà présent pour DOCX ?). Aujourd'hui ces fichiers sont rejetés en upload
direct (et silencieusement ignorés dans les ZIP), ce qui surprend.

### O8 — Détails de moindre priorité
- `GedService.addTags` : `merged.contains(t)` O(n²) — utiliser un `LinkedHashSet`.
- `ChunkingService` : `BreakIterator.getSentenceInstance(Locale.FRENCH)` codé en dur — pour un
  corpus multilingue, prévoir une locale configurable.
- `ConsistencyReconciliationService` compare `chroma` au FTS de la seule collection par défaut :
  les collections de flux Kafka et de ré-indexation ne sont pas surveillées.
- `IngestionService.getAllTasks()` sans tri : l'UI reçoit les tâches dans un ordre aléatoire de
  `ConcurrentHashMap` — trier par `createdAt` desc.
- `JsonExtractor.flattenJson` : récursion non bornée en profondeur (les
  `StreamReadConstraints` Jackson ≥ 2.15 protègent à ~1000 niveaux — vérifier la version
  effective, sinon borner explicitement).

---

## 3. Points positifs notables (à préserver)

- Dédup SHA-256 en streaming (`DigestInputStream` vers fichier temp) : pas de chargement mémoire.
- Protections ZIP-bomb complètes : profondeur, nombre d'entrées, `LimitedInputStream`, path traversal.
- `upsertFromStream` (Kafka) : tombstone + idempotence par hash de contenu + delete-then-index —
  c'est le modèle à généraliser aux ré-ingestions (B1/B6).
- Fenêtre DNS-rebinding fermée sur le fetch direct d'URL (`doAfterResolve`), redirections
  désactivées, plages privées/CGNAT/ULA filtrées.
- Estampillage du modèle d'embedding par collection avec blocage en cas de mismatch.
- Chunking incrémental en tokens (plus de ré-encodage quadratique), coupures aux frontières de
  phrases, progression stricte garantie.
- OOM converti en échec de tâche + libération du sémaphore ; nettoyage des temp orphelins.

---

## 4. Priorisation et statut des correctifs

| # | Sujet | Impact | Statut |
|---|-------|--------|--------|
| 1 | B1 + B6 — purge avant ré-ingestion (upsert par document) | Intégrité du retrieval | ✅ Corrigé — `beforeIndex` + `purgeForReingestion` (le `force` répare désormais un document partiel sans dupliquer) |
| 2 | B2 — purge de rétention via `GedService.deleteDocument` | Fuite de données « purgées » | ✅ Corrigé |
| 3 | B4 — unifier les deux chemins de suppression | Cohérence GED ↔ index | ✅ Corrigé — `GedService.deleteBySourceFile`, utilisé par `DELETE /api/documents/{sourceFile}` |
| 4 | B3 — identité des chunks par sha256 | Collisions de noms | ✅ Corrigé côté ChromaDB (métadonnée `sha256` + suppression par identité, repli `sourceFile` pour les chunks historiques). BM25 reste par `sourceFile` (résiduel mineur) |
| 5 | B5 — remonter les erreurs par fichier dans `IngestionTask` | Confiance utilisateur | ✅ Corrigé — champ `fileErrors`, statut `FAILED` quand tout échoue (upload, exécuteur, URLs) |
| 6 | B7/B8 — borne mémoire + concurrence sur URL/upload direct | Stabilité | ✅ Corrigé — `ingestOneWithPermit` (sémaphore) + réservation in-flight sur `ingest()` + limite de taille dans `ingestOne` |
| 7 | O1–O4 — optimisations réseau/SQL | Débit d'ingestion | ✅ O1 (delete par `where`), O2 (deletes SQL en masse), O3 (`HexFormat`) ; O4 (batch d'embedding) laissé en réglage de config |

Également corrigés :

- **B9 (complet)** — `incrementVersion` rafraîchit `ingestedAt`, et la purge de rétention s'appuie
  sur la nouvelle colonne `archivedAt` (posée à la transition vers ARCHIVED, effacée au retour ;
  repli `ingestedAt` pour les lignes historiques).
- **B10** — le rebuild FTS **fusionne** avec l'index vivant (`BM25Index.addAll` +
  `ConcurrentHashMap.merge`) au lieu de l'écraser : les chunks indexés pendant le rebuild ne
  disparaissent plus de BM25. Le retry planifié ne concerne plus que les rebuilds en échec
  (une collection légitimement vide ne déclenche plus un rebuild/minute à vie).
- **B12** — les réservations in-flight sont rafraîchies par un heartbeat à chaque lot
  d'embeddings (upload et URL) : une ingestion plus longue que le TTL n'est plus « reprise »
  par un doublon. Une tâche `force` ne libère plus les réservations d'une tâche concurrente.
- **B11** — score de qualité atteint 1.0 (arrondi 4 décimales).
- **B13** — échappement du filtre tag, collection réelle enregistrée par `ingest()`,
  `getAllTasks()` trié (plus récent d'abord).
- **O5** — `listSources` agrège page par page en ne demandant que les métadonnées.
- **O6** — flush FTS espacé à 30 s (sérialisation complète moins fréquente ; `@PreDestroy`
  couvre l'arrêt propre).
- **O7** — `.md`/`.markdown`/`.csv` routés vers l'extracteur texte.
- **O8** — `addTags` en `LinkedHashSet` (O(1)).

Troisième vague — les quatre derniers points ouverts sont traités :

- **Locale de chunking configurable** — `spectra.pipeline.chunk-locale` (tag BCP 47, défaut
  `fr`) pilote le `BreakIterator` des frontières de phrases ; valeur invalide → repli français.
- **Réconciliation multi-collections** — `ConsistencyReconciliationService` surveille désormais
  la collection par défaut, celles référencées par la GED et celle du flux Kafka (si activé),
  avec des gauges par collection (`spectra.consistency.collection.*{collection=…}`) en plus des
  gauges historiques ; la réparation FTS s'applique à chacune.
- **Transition TRAINED automatique** — en fin de fine-tuning réussi, les documents sources du
  dataset (provenance `source` des paires SFT/DPO) sont liés au modèle (`TRAINED_ON`) et leur
  cycle de vie avance vers TRAINED en respectant la machine à états (INGESTED → QUALIFIED →
  TRAINED ; ARCHIVED n'est pas ressuscité). Le statut TRAINED était en pratique inatteignable
  (liens uniquement manuels).
- **Borne de profondeur JSON** — `JsonExtractor` limite l'aplatissement à 128 niveaux ; au-delà,
  le sous-arbre est sérialisé en bloc (tronqué à 4 096 caractères) au lieu de poursuivre la
  récursion — plus de dépendance implicite aux `StreamReadConstraints` de Jackson.

Au passage : restauration de la validation de fraîcheur de l'index FTS disque contre ChromaDB
(`diskIndexMatchesChroma`), dont l'implémentation avait été perdue lors d'une fusion sur `main`
(le commentaire et le test existaient, pas le code — la suite était rouge).

L'audit est intégralement traité : plus aucun point ouvert.
