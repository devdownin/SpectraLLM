# Design — Ingestion streaming Kafka → RAG avec upsert

> Statut : proposition de design (référence d'implémentation).
> Objectif : **enrichir le LLM au fil de l'eau avec des données vivantes** en
> consommant des messages Kafka et en maintenant l'index RAG à jour (ajout,
> mise à jour, suppression) sans réentraînement.

## 1. Positionnement : quelle voie d'enrichissement ?

Spectra enrichit le modèle de deux façons :

| Voie | Continu / temps réel ? | Adapté au flux Kafka ? |
|---|---|---|
| **RAG** (indexation → ChromaDB + BM25, récupéré à la question) | Oui, immédiat : un message indexé est aussitôt récupérable | **Oui — c'est la cible** |
| **Fine-tuning** (poids, QLoRA) | Non : job batch, lourd, périodique | Non au message (boucle batch séparée, hors périmètre) |

« Au fil de l'eau » = **RAG en streaming**. Le pipeline existant
`IngestionService.ingest(...)` indexe le contenu ; dès la question suivante, le
LLM y a accès. La **connaissance effective** du système se met à jour en
secondes, sans toucher aux poids.

## 2. Découverte préalable (bug à corriger — lot L0)

L'upsert repose sur la suppression de l'ancienne version par source. Or il
existait une asymétrie entre les deux index :

| Index | Suppression | État initial |
|---|---|---|
| BM25 (`FtsService.removeBySource`) | champ `TextChunk.sourceFile` | fonctionne pour tous les formats |
| ChromaDB (`ChromaDbClient.deleteBySource`) | filtre métadonnée `where sourceFile == X` | ne matchait rien pour la plupart des formats |

Cause : `ChunkingService.createChunk` n'injectait que `chunkIndex` dans la
métadonnée ; `sourceFile` n'y était ajouté que par `TxtExtractor`. Pour PDF,
DOCX, JSON, Avro, XML, la métadonnée ChromaDB ne contenait pas `sourceFile`, donc
`deleteBySource` renvoyait 0 et les anciens chunks survivaient.

**Correctif (appliqué)** — dans `ChunkingService.createChunk` :

```java
metadata.put("chunkIndex", String.valueOf(index));
if (sourceFile != null) {
    metadata.put("sourceFile", sourceFile); // deleteBySource OK pour TOUS les formats
}
```

C'est aussi un bugfix pour la suppression manuelle existante
(`DocumentController` → `deleteBySource`). Sans lui, tout l'upsert est
silencieusement cassé côté vecteur. Verrouillé par
`ChunkingServiceTest.chunk_propagatesSourceFileIntoMetadataForVectorDelete`.

## 3. Principe : la clé Kafka devient la clé d'identité RAG

`sourceFile` = **clé métier stable** dérivée du message. Toute nouvelle version
d'une même entité réutilise le même `sourceFile` → « delete-then-index » = upsert.

```
Message Kafka
  key   = "dossier-4271"          (identité métier, stable)
  value = {statut:"clos", ...}    (état courant, mutable)
        |
        v
sourceFile = "kafka://commandes/dossier-4271"
        |
        +- deleteBySource(sourceFile)   -> purge ChromaDB + BM25 (ancienne version)
        +- index(sourceFile, value)     -> réindexe la version courante
```

Convention de nommage : `kafka://<topic>/<key>`. Sans clé métier, fallback
`kafka://<topic>/<partition>-<offset>` (append-only, pas d'upsert — flux
purement événementiels).

## 4. Nouveau flux d'ingestion `upsertFromStream`

`IngestionService.ingest(...)` fait une **dédup par SHA-256 du contenu** (skip si
le hash existe) — inadapté à un upsert (on veut remplacer). D'où une nouvelle
méthode dédiée :

```java
public UpsertResult upsertFromStream(String sourceKey, String logicalName,
                                     InputStream content, String collectionName) throws Exception {
    String collectionId = chromaDbClient.getOrCreateCollection(collectionName);

    // 1. extraction (routage via logicalName, ex. "....json") -> clean -> chunk
    var doc = extractorFactory.getExtractor(extractorFactory.resolveContentType(logicalName))
                              .extract(logicalName, content);
    String cleaned = textCleaner.clean(doc.text());
    List<TextChunk> chunks = chunkingService.chunk(cleaned, sourceKey, doc.metadata()); // sourceFile = sourceKey

    // 2. tombstone : contenu vide -> suppression pure (donnée supprimée en amont)
    if (chunks.isEmpty()) {
        int removed = purge(collectionId, collectionName, sourceKey);
        return UpsertResult.deleted(sourceKey, removed);
    }

    // 3. idempotence : hash inchangé -> ne rien faire (rejeu at-least-once)
    String contentHash = sha256(cleaned);
    if (contentHash.equals(lastHashOf(sourceKey))) {
        return UpsertResult.unchanged(sourceKey);
    }

    // 4. DELETE ancienne version (les deux index)
    int removed = purge(collectionId, collectionName, sourceKey);

    // 5. INDEX version courante (embed par lot + Chroma.add + FtsService.indexChunks)
    indexChunks(collectionId, collectionName, chunks);

    // 6. traçabilité + version
    recordUpsert(sourceKey, logicalName, chunks.size(), contentHash, collectionName);
    return UpsertResult.upserted(sourceKey, removed, chunks.size());
}

private int purge(String collectionId, String collectionName, String sourceKey) {
    int a = chromaDbClient.deleteBySource(collectionId, sourceKey);
    ftsService.removeBySource(sourceKey, collectionName);
    return a;
}
```

Points clés :
- `chunk(cleaned, sourceKey, ...)` : on passe la **clé métier** comme
  `sourceFile` (pas le nom de fichier). Combiné au correctif §2, `deleteBySource`
  fonctionne.
- **Tombstone** : message avec `value == null` (log-compaction Kafka) → chunks
  vides → purge pure.
- **Idempotence** par hash de clé (étape 3) : évite la réindexation inutile sur
  rejeu (nécessite le dernier hash par `sourceKey` — voir §6).

## 5. Consumer Kafka (activé conditionnellement)

```java
@Service
@ConditionalOnProperty(prefix = "spectra.kafka", name = "enabled", havingValue = "true")
public class KafkaIngestionListener {

    @KafkaListener(topics = "#{@kafkaTopics}", groupId = "#{@kafkaGroupId}",
                   concurrency = "#{@kafkaConcurrency}")
    public void onMessage(ConsumerRecord<String, byte[]> rec, Acknowledgment ack) {
        String sourceKey = rec.key() != null
            ? "kafka://%s/%s".formatted(rec.topic(), rec.key())
            : "kafka://%s/%d-%d".formatted(rec.topic(), rec.partition(), rec.offset());
        String logicalName = sourceKey + "." + cfg.effectiveFormat(); // route l'extracteur
        InputStream body = new ByteArrayInputStream(
            rec.value() != null ? rec.value() : new byte[0]);          // null = tombstone
        try {
            ingestionService.upsertFromStream(sourceKey, logicalName, body, cfg.effectiveCollection());
            ack.acknowledge();               // commit APRÈS indexation -> at-least-once
        } catch (Exception e) {
            throw new ListenerExecutionFailedException("upsert échoué: " + sourceKey, e); // -> DLT
        }
    }
}
```

Configuration Spring Kafka (`KafkaConfig`, `@ConditionalOnProperty`) :
- `enable.auto.commit=false` + `AckMode.MANUAL` → **at-least-once**.
- `DefaultErrorHandler` avec backoff (ex. 3 essais) puis
  `DeadLetterPublishingRecoverer` → topic `<topic>.DLT` : un message
  inextractable ne bloque jamais la partition.
- `max.poll.records` bas (10–50) : chaque message déclenche des embeddings (I/O).

## 6. Persistance : suivre l'état par clé métier

`IngestedFileEntity` est keyé par SHA-256 de contenu → inadapté. Table dédiée :

```java
@Entity @Table(name = "kafka_stream_source")
class StreamSourceEntity {
    @Id String sourceKey;   // "kafka://commandes/dossier-4271"
    String collection;
    String contentHash;     // dernier SHA-256 du texte nettoyé -> idempotence (§4.3)
    int chunkCount;
    long version;           // incrémentée à chaque upsert effectif
    Instant firstSeenAt;
    Instant lastUpdatedAt;  // -> rétention temporelle (§7)
    String lastOffsetRef;   // topic/partition/offset (diagnostic/replay)
}
```

`lastHashOf(sourceKey)` lit `contentHash` (idempotence sans relire ChromaDB).
Audit GED réutilisable via `gedService.audit(...)` avec `sourceKey` en référence.

## 7. Rétention / données périmées (le flux est infini)

Un flux continu fait croître l'index sans limite → latence + bruit. Réutilise le
pattern `RetentionPolicyService` (cron nocturne déjà en place) :

```java
@Scheduled(cron = "${spectra.kafka.retention-cron:0 30 3 * * *}")
void purgeStale() {
    if (ttlDays <= 0) return;                 // 0 = désactivé
    Instant cutoff = Instant.now().minus(ttlDays, DAYS);
    for (StreamSourceEntity s : repo.findByLastUpdatedAtBefore(cutoff)) {
        purge(collectionId(s.collection), s.collection, s.sourceKey);
        repo.delete(s);
    }
}
```

Config : `SPECTRA_KAFKA_RETENTION_TTL_DAYS` (0 = désactivé). Complémentaire d'une
éventuelle log-compaction côté Kafka.

## 8. Fraîcheur temporelle au retrieval (phase 2, optionnel)

Le RAG classe par similarité sémantique, pas par récence. Pour données vivantes :
- `metadata.put("ingestedAt", Instant.now().toString())` et/ou `eventTime`
  (depuis `rec.timestamp()`).
- Phase 2 : décote de récence légère à la fusion, ou filtre `where` temporel.
  Hors MVP — activable par flag si le besoin est confirmé.

## 9. Performance — points concrets vus dans le code

1. `FtsService` sérialise **tout l'index sur disque à chaque `indexChunks`**
   (`saveIndexToDisk`). À haute fréquence Kafka → goulot majeur. **Recommandation :
   flush *debounced*** (périodique, ex. 5 s ou tous les N upserts) plutôt qu'à
   chaque message.
2. `deleteBySource` = 2 appels HTTP ChromaDB (get IDs puis delete) par upsert.
   Calibrer `concurrency` pour ne pas saturer ChromaDB/llm-embed (le vrai plafond
   de débit reste l'embedding).
3. Embedding synchrone par message : garder `max.poll.records` bas, s'appuyer sur
   les virtual threads déjà actifs.

## 10. Configuration

Nouveau record dans `SpectraProperties` (pattern `RerankerProperties`) :

```java
public record KafkaProperties(
    Boolean enabled, String bootstrapServers, List<String> topics,
    String groupId, String collection, String format,     // json|avro|text
    Integer concurrency, Integer maxPollRecords,
    Integer retentionTtlDays, SecurityProperties security) {
  public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
  public String effectiveFormat() { return format != null ? format : "json"; }
  public String effectiveCollection() { return collection != null ? collection : "spectra_stream"; }
  public int effectiveConcurrency() { return concurrency != null ? concurrency : 1; }
  // security: protocol (PLAINTEXT|SASL_SSL), mechanism (SCRAM-SHA-512…), user, secret-ref
}
```

Variables d'env : `SPECTRA_KAFKA_ENABLED`, `SPECTRA_KAFKA_BOOTSTRAP_SERVERS`,
`SPECTRA_KAFKA_TOPICS`, `SPECTRA_KAFKA_GROUP_ID`, `SPECTRA_KAFKA_COLLECTION`,
`SPECTRA_KAFKA_FORMAT`, `SPECTRA_KAFKA_CONCURRENCY`,
`SPECTRA_KAFKA_RETENTION_TTL_DAYS`, `SPECTRA_KAFKA_SECURITY_*`.

**Collection dédiée** (`spectra_stream`) recommandée : isole les données vivantes
du corpus documentaire statique, permet des rétentions distinctes, évite de
polluer la recherche documentaire.

## 11. Séquence complète (message → réponse RAG)

```
Kafka topic --rec(key,value)--> KafkaIngestionListener
                                   | sourceKey = kafka://topic/key
                                   v
                     IngestionService.upsertFromStream
        +---------------+----------------+---------------+
        | extract+clean | hash inchangé? | value==null?  |
        |  +chunk       |  -> unchanged  |  -> purge only|
        +------+--------+-------+--------+------+--------+
               v (changé)      (skip)          v (tombstone)
        deleteBySource(Chroma) + removeBySource(BM25)
               v
        embed(batch) -> Chroma.add + FtsService.indexChunks
               v
        StreamSourceEntity.upsert(version++, contentHash, lastUpdatedAt)
               v
        ack.acknowledge()  -- commit offset

   ... (plus tard) QueryController -> RagService : version courante déjà interrogeable
```

## 12. Tests

- Unitaire `upsertFromStream` : (a) nouvelle clé → N chunks ; (b) même clé,
  contenu changé → anciens supprimés + nouveaux présents (count stable, pas
  d'accumulation) ; (c) contenu identique → `unchanged`, 0 delete/index ;
  (d) `value=null` → purge, 0 chunk.
- Correctif L0 : métadonnée ChromaDB contient `sourceFile` (protège
  `deleteBySource`). **Fait** (`ChunkingServiceTest`).
- Listener avec `spring-kafka-test` (`EmbeddedKafka`) : commit manuel, routage
  DLT sur message corrompu, `concurrency`.
- Idempotence rejeu : rejouer le même offset → pas de doublon.

## 13. Découpage & effort

| Lot | Contenu | Effort | Statut |
|---|---|---|---|
| **L0** | Correctif `sourceFile` en métadonnée + test (bugfix autonome) | 0,5 j | **fait** |
| **L1 (MVP)** | `KafkaProperties`, `KafkaConfig`, listener, `upsertFromStream`, `StreamSourceEntity`, commit manuel + DLT, tests | 1,5–2 j | **fait** |
| **L2** | Rétention TTL (`KafkaStreamRetentionService`, `@Scheduled`) + profil Docker compose `kafka` (KRaft) + doc README | 0,5–1 j | **fait** |
| **L3** | Fraîcheur temporelle (`ingestedAt`/`eventTime`), mapping de champs configurable (`KafkaPayloadMapper`), métriques Micrometer | 1–2 j | **fait** |

Risques principaux maîtrisés : le correctif L0 (sinon upsert cassé côté vecteur,
**corrigé**) et le flush BM25 *debounced* (§9, sinon débit effondré).
</content>
