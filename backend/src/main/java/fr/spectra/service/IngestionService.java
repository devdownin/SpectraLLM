package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.IngestionTask;
import fr.spectra.model.ExtractedDocument;
import fr.spectra.model.TextChunk;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.persistence.StreamSourceEntity;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import org.springframework.scheduling.annotation.Scheduled;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service d'ingestion — transforme un fichier brut en chunks vectorisés interrogeables.
 *
 * <p><b>Pourquoi cette étape ?</b> Un LLM ne « connaît » pas vos documents. Pour qu'il
 * puisse répondre à partir de leur contenu (RAG), il faut d'abord découper chaque document
 * en petits passages (chunks), calculer une représentation numérique (embedding) de chacun,
 * puis stocker ces vecteurs dans une base vectorielle. C'est exactement ce que fait ce service ;
 * la recherche au moment de la question est ensuite assurée par {@link RagService}.</p>
 *
 * <p><b>Pipeline d'ingestion (par fichier) :</b></p>
 * <ol>
 *   <li><b>Déduplication SHA-256</b> — on hashe le flux d'octets ; un fichier déjà ingéré
 *       est ignoré (sauf {@code force=true}). Évite de gonfler l'index avec des doublons.</li>
 *   <li><b>Extraction</b> — un {@link DocumentExtractorFactory extracteur} dédié au format
 *       (PDF, DOCX, HTML, XLSX, JSON, Avro…) produit du texte brut. Les archives ZIP sont
 *       parcourues récursivement (profondeur max {@value #MAX_ZIP_DEPTH}).</li>
 *   <li><b>Nettoyage</b> — {@link TextCleanerService} normalise le texte (espaces, césures,
 *       artefacts d'OCR…) pour améliorer la qualité des embeddings.</li>
 *   <li><b>Chunking</b> — {@link ChunkingService} découpe en passages avec chevauchement.</li>
 *   <li><b>Embedding</b> — {@link EmbeddingService} vectorise les chunks par lots
 *       ({@code embeddingBatchSize}) pour limiter les allers-retours réseau.</li>
 *   <li><b>Indexation</b> — les vecteurs partent dans {@link ChromaDbClient} (recherche
 *       sémantique) et le texte dans {@link FtsService} (recherche lexicale BM25), ce qui
 *       rend possible la recherche hybride.</li>
 *   <li><b>Traçabilité GED</b> — {@link GedService} enregistre le document dans la gestion
 *       documentaire (audit, qualification automatique au-delà d'un seuil de score).</li>
 * </ol>
 *
 * <p><b>Robustesse mémoire & sécurité :</b> les gros fichiers et les « ZIP bombs » (archives
 * minuscules qui se décompressent en téraoctets) sont bornés par {@code maxUncompressedBytes}
 * — auto-calculé à partir du heap JVM et de la concurrence d'ingestion — et par
 * {@value #MAX_ZIP_ENTRIES} entrées max par archive. L'ingestion lourde tourne de façon
 * asynchrone via {@link IngestionTaskExecutor}, avec suivi de progression, annulation
 * ({@code cancelledTaskIds}) et purge mémoire planifiée des tâches terminées.</p>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final String DEFAULT_COLLECTION = "spectra_documents";
    private static final int MAX_ZIP_DEPTH = 3;
    /** Nombre maximal d'entrées traitées par archive (protection ZIP bomb). */
    private static final int MAX_ZIP_ENTRIES = 10_000;

    // Direct-pipeline deps (used by ingest() and ingestLocalFiles())
    private final DocumentExtractorFactory extractorFactory;
    private final TextCleanerService textCleaner;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final ChromaDbClient chromaDbClient;
    private final FtsService ftsService;
    private final int embeddingBatchSize;
    /** Taille décompressée maximale par fichier local (mémoire ; garde-fou avant délégation). */
    private final long maxUncompressedBytes;

    // Async + dedup deps (used by submit())
    private final IngestionTaskExecutor executor;
    private final IngestedFileRepository repository;
    private final GedService gedService;
    private final String defaultCollection;
    private final double autoQualifyThreshold;

    // Streaming (Kafka) upsert deps
    private final fr.spectra.persistence.StreamSourceRepository streamSourceRepository;

    private final Map<String, IngestionTask> tasks = new ConcurrentHashMap<>();
    private final Set<String> cancelledTaskIds = ConcurrentHashMap.newKeySet();
    /**
     * Hachages en cours d'ingestion (hash → instant de réservation). Empêche deux uploads
     * simultanés du MÊME contenu d'indexer chacun ses chunks (doublons vectoriels) avant que
     * la ligne GED n'existe. La réservation se libère au succès ; une réservation périmée
     * (ingestion échouée/crashée) est automatiquement reprise après {@link #INFLIGHT_TTL}.
     */
    private final Map<String, Instant> inFlightHashes = new ConcurrentHashMap<>();
    private static final Duration INFLIGHT_TTL = Duration.ofMinutes(15);
    /** Âge au-delà duquel un temp d'ingestion est considéré orphelin (bien > durée d'ingestion). */
    private static final Duration ORPHAN_TEMP_TTL = Duration.ofHours(2);

    public IngestionService(DocumentExtractorFactory extractorFactory,
                            TextCleanerService textCleaner,
                            ChunkingService chunkingService,
                            EmbeddingService embeddingService,
                            ChromaDbClient chromaDbClient,
                            FtsService ftsService,
                            IngestionTaskExecutor executor,
                            IngestedFileRepository repository,
                            GedService gedService,
                            fr.spectra.persistence.StreamSourceRepository streamSourceRepository,
                            SpectraProperties properties,
                            @org.springframework.beans.factory.annotation.Value("${spectra.pipeline.max-uncompressed-mb:0}") int maxUncompressedMb,
                            @org.springframework.beans.factory.annotation.Value("${spectra.pipeline.concurrent-ingestions:4}") int concurrentIngestions) {
        // 0 → auto-calcul selon le heap et la concurrence (évite l'OOM).
        this.maxUncompressedBytes = IngestionLimits.resolveMaxUncompressedBytes(maxUncompressedMb, concurrentIngestions);
        this.extractorFactory = extractorFactory;
        this.textCleaner = textCleaner;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.chromaDbClient = chromaDbClient;
        this.ftsService = ftsService;
        this.executor = executor;
        this.repository = repository;
        this.gedService = gedService;
        this.streamSourceRepository = streamSourceRepository;
        this.embeddingBatchSize = properties.pipeline().embeddingBatchSize();
        this.defaultCollection = properties.chromadb() != null
                ? properties.chromadb().effectiveCollection()
                : DEFAULT_COLLECTION;
        this.autoQualifyThreshold = properties.ged() != null
                ? properties.ged().effectiveAutoQualifyThreshold()
                : 0.0;
    }

    /**
     * Lance une ingestion asynchrone avec déduplication SHA-256.
     * Les fichiers déjà ingérés sont ignorés sauf si {@code force=true}.
     */
    public String submit(List<MultipartFile> files, boolean force) {
        String taskId = UUID.randomUUID().toString();
        List<String> allFileNames = files.stream().map(MultipartFile::getOriginalFilename).toList();

        List<String> toProcessNames = new ArrayList<>();
        List<Path> tempFiles = new ArrayList<>();
        Map<Path, String> tempFileToHash = new LinkedHashMap<>();

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("spectra-ingest-");
        } catch (Exception e) {
            log.error("Impossible de créer le répertoire temp: {}", e.getMessage());
            tasks.put(taskId, IngestionTask.pending(taskId, allFileNames).failed(e.getMessage()));
            return taskId;
        }

        // Record all items to check in bulk
        List<Path> allTempFiles = new ArrayList<>();
        List<String> allHashes = new ArrayList<>();
        List<String> allFileNamesOrdered = new ArrayList<>();

        for (MultipartFile file : files) {
            String fileName = file.getOriginalFilename();
            Path tempFile = tempDir.resolve(UUID.randomUUID() + "_" + (fileName != null ? fileName : "unknown"));
            try {
                String hash = copyAndHash(file.getInputStream(), tempFile);

                allTempFiles.add(tempFile);
                allHashes.add(hash);
                allFileNamesOrdered.add(fileName);
            } catch (Exception e) {
                log.warn("Erreur préparation fichier {}: {}", fileName, e.getMessage());
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }

        // Bulk check for existing files
        Set<String> existingHashes = new java.util.HashSet<>();
        if (!force && !allHashes.isEmpty()) {
            repository.findAllById(allHashes).forEach(entity -> existingHashes.add(entity.getSha256()));
        }

        for (int i = 0; i < allTempFiles.size(); i++) {
            Path tempFile = allTempFiles.get(i);
            String hash = allHashes.get(i);
            String fileName = allFileNamesOrdered.get(i);

            // Ignorer si déjà en base OU si une ingestion concurrente du même contenu est en
            // cours (réservation in-flight) — évite la double indexation des mêmes chunks.
            if (!force && (existingHashes.contains(hash) || !tryClaimHash(hash))) {
                log.info("Fichier ignoré (déjà ingéré ou ingestion concurrente, sha256={}): {}", hash, fileName);
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
                continue;
            }

            tempFiles.add(tempFile);
            tempFileToHash.put(tempFile, hash);
            toProcessNames.add(fileName);
        }

        if (tempFiles.isEmpty()) {
            log.info("Tous les fichiers déjà ingérés — tâche {} terminée immédiatement", taskId);
            tasks.put(taskId, IngestionTask.pending(taskId, allFileNames).completed(0));
            try { Files.delete(tempDir); } catch (Exception ignored) {}
            return taskId;
        }

        tasks.put(taskId, IngestionTask.pending(taskId, allFileNames));
        executor.execute(taskId, toProcessNames, tempFiles, tasks, defaultCollection, tempFileToHash,
                (hash, fileName, chunks) -> {
                    recordIngestion(hash, fileName, chunks);
                    inFlightHashes.remove(hash); // libère la réservation : la dédup DB prend le relais
                }, tempDir);

        return taskId;
    }

    /**
     * Réserve un hachage pour l'ingestion. Retourne {@code false} si une ingestion du même
     * contenu est déjà en cours (réservation récente). Une réservation périmée (au-delà de
     * {@link #INFLIGHT_TTL}) est reprise atomiquement — une ingestion échouée n'est jamais
     * bloquée indéfiniment.
     */
    private boolean tryClaimHash(String hash) {
        Instant now = Instant.now();
        Instant prev = inFlightHashes.putIfAbsent(hash, now);
        if (prev == null) return true;
        if (Duration.between(prev, now).compareTo(INFLIGHT_TTL) > 0) {
            return inFlightHashes.replace(hash, prev, now);
        }
        return false;
    }

    /** Compatibilité ascendante — soumet sans forcer la ré-ingestion. */
    public String submit(List<MultipartFile> files) {
        return submit(files, false);
    }

    public IngestionTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    public List<IngestionTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * Annule une tâche en cours. La tâche en exécution sera interrompue au prochain
     * point de contrôle dans l'exécuteur.
     */
    public boolean cancelTask(String taskId) {
        // Mise à jour ATOMIQUE : l'ancien enchaînement get() puis put() pouvait écraser un
        // statut COMPLETED/FAILED posé entre-temps par l'exécuteur (course annulation/fin).
        cancelledTaskIds.add(taskId);
        final boolean[] cancelled = {false};
        tasks.computeIfPresent(taskId, (k, t) -> {
            if (t.status() == IngestionTask.Status.COMPLETED
                    || t.status() == IngestionTask.Status.FAILED
                    || t.status() == IngestionTask.Status.CANCELLED) {
                return t;
            }
            cancelled[0] = true;
            return t.cancelled();
        });
        if (!cancelled[0]) cancelledTaskIds.remove(taskId);
        return cancelled[0];
    }

    public boolean isCancelled(String taskId) {
        return cancelledTaskIds.contains(taskId);
    }

    /** Supprime les tâches terminées / annulées datant de plus d'une heure. */
    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupOldTasks() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        tasks.entrySet().removeIf(e -> {
            IngestionTask t = e.getValue();
            return (t.status() == IngestionTask.Status.COMPLETED
                    || t.status() == IngestionTask.Status.FAILED
                    || t.status() == IngestionTask.Status.CANCELLED)
                    && t.completedAt() != null && t.completedAt().isBefore(cutoff);
        });
        cancelledTaskIds.removeIf(id -> !tasks.containsKey(id));
        // Purge les réservations in-flight périmées (ingestions échouées/crashées) pour borner la mémoire.
        Instant claimCutoff = Instant.now().minus(INFLIGHT_TTL);
        inFlightHashes.entrySet().removeIf(e -> e.getValue().isBefore(claimCutoff));
    }

    /**
     * Supprime les fichiers/répertoires temporaires d'ingestion orphelins. En fonctionnement
     * normal le {@code finally} de l'exécuteur les nettoie ; mais un arrêt brutal de la JVM
     * (kill, OOM) mid-ingestion peut laisser des {@code spectra-ingest-*}/{@code spectra-url-*}
     * dans le répertoire temp. On ne supprime que ceux plus vieux que {@link #ORPHAN_TEMP_TTL}
     * — bien au-delà de toute ingestion en cours — pour ne jamais toucher un travail actif.
     */
    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupOrphanedTempFiles() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long cutoffMillis = System.currentTimeMillis() - ORPHAN_TEMP_TTL.toMillis();
        try (var entries = Files.list(tmp)) {
            entries.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("spectra-ingest-") || n.startsWith("spectra-url-");
                    })
                    .filter(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis() < cutoffMillis; }
                        catch (Exception e) { return false; }
                    })
                    .forEach(this::deleteRecursively);
        } catch (Exception e) {
            log.debug("Balayage des fichiers temp orphelins impossible: {}", e.getMessage());
        }
    }

    private void deleteRecursively(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (var walk = Files.walk(p)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception ignored) {} });
                }
            } else {
                Files.deleteIfExists(p);
            }
            log.info("Temp d'ingestion orphelin supprimé (ingestion interrompue): {}", p.getFileName());
        } catch (Exception e) {
            log.debug("Suppression du temp orphelin {} impossible: {}", p, e.getMessage());
        }
    }

    public Page<IngestedFileEntity> getHistory(int page, int size, String q) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200),
                Sort.by(Sort.Direction.DESC, "ingestedAt"));
        if (q != null && !q.isBlank()) {
            return repository.findByFileNameContainingIgnoreCase(q.trim(), pageable);
        }
        return repository.findAll(pageable);
    }

    public IngestionTask registerTask(String taskId, List<String> fileNames) {
        IngestionTask task = IngestionTask.pending(taskId, fileNames);
        tasks.put(taskId, task);
        return task;
    }

    public void updateTask(String taskId, IngestionTask task) {
        tasks.put(taskId, task);
    }

    /** Persiste un hash en base après ingestion réussie. */
    public void recordIngestion(String hash, String fileName, int chunks) {
        recordIngestion(hash, fileName, chunks, defaultCollection);
    }

    /** Persiste un hash en base après ingestion réussie (avec collection explicite). */
    public void recordIngestion(String hash, String fileName, int chunks, String collection) {
        if (hash == null) return;
        try {
            String format = fileName != null && fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf('.') + 1).toUpperCase()
                    : "UNKNOWN";
            double qualityScore = GedService.computeQualityScore(chunks, format);

            boolean alreadyExists = repository.existsById(hash);
            if (alreadyExists) {
                // R4 — re-ingestion : incrémenter la version
                gedService.incrementVersion(hash, "system");
            } else {
                IngestedFileEntity entity = new IngestedFileEntity(
                        hash, fileName, format, Instant.now(), chunks, collection, qualityScore);
                repository.save(entity);
                // R6 — audit initial
                gedService.audit(hash, fr.spectra.persistence.AuditLogEntity.Action.INGESTED,
                        "system",
                        java.util.Map.of("chunks", String.valueOf(chunks),
                                         "quality", String.format("%.2f", qualityScore),
                                         "collection", collection != null ? collection : ""));
                // Amélioration 4 — auto-qualification si score ≥ seuil configuré
                if (autoQualifyThreshold > 0.0 && qualityScore >= autoQualifyThreshold) {
                    try {
                        gedService.transitionLifecycle(hash,
                                fr.spectra.persistence.IngestedFileEntity.Lifecycle.QUALIFIED, "auto-qualify");
                        log.info("Document {} auto-qualifié (score={}, seuil={})", hash, qualityScore, autoQualifyThreshold);
                    } catch (Exception e) {
                        log.warn("Auto-qualification échouée pour {} : {}", hash, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Erreur persistance ingestion {}: {}", hash, e.getMessage());
        }
    }

    /**
     * Ingère un seul fichier depuis un InputStream (utilisé par UrlIngestionService).
     * Stream via un fichier temporaire pour éviter de charger l'intégralité en mémoire.
     */
    public int ingest(String fileName, InputStream inputStream, String collectionId) throws Exception {
        Path tempFile = Files.createTempFile("spectra-url-", null);
        try {
            String hash = copyAndHash(inputStream, tempFile);
            if (repository.existsById(hash)) {
                log.info("Fichier ignoré (déjà ingéré, sha256={}): {}", hash, fileName);
                return 0;
            }
            // Pipeline unique : on délègue à l'exécuteur (extraction → chunking → embedding →
            // indexation, gestion .zip et comptage partiel inclus) au lieu de dupliquer la logique.
            IngestionTaskExecutor.IngestOneResult r =
                    executor.ingestOne(fileName, tempFile, collectionId, defaultCollection, i -> {});
            if (r.chunks() > 0) {
                recordIngestion(hash, fileName, r.chunks(), defaultCollection);
            }
            return r.chunks();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Ingère une liste de fichiers locaux de façon synchrone (utilisé par BatchService).
     */
    public int ingestLocalFiles(List<Path> paths) {
        try {
            String collectionId = chromaDbClient.getOrCreateCollection(defaultCollection);
            int total = 0;
            for (Path path : paths) {
                try {
                    long fileSize = Files.size(path);
                    if (fileSize > maxUncompressedBytes) {
                        log.warn("Fichier ignoré (trop grand {}MB > {}MB): {}",
                                fileSize / (1024 * 1024), maxUncompressedBytes / (1024 * 1024), path);
                        continue;
                    }
                    // Hash en streaming (pas de chargement complet en mémoire).
                    String hash;
                    try (InputStream in = Files.newInputStream(path)) {
                        hash = sha256(in);
                    }
                    // Dédup : ne pas ré-indexer un document déjà ingéré (sinon chaque relance
                    // du batch duplique tous les chunks dans ChromaDB et BM25).
                    if (repository.existsById(hash)) {
                        log.info("Fichier ignoré (déjà ingéré, sha256={}): {}", hash, path.getFileName());
                        continue;
                    }
                    // Pipeline unique via l'exécuteur (gestion .zip + comptage partiel inclus).
                    IngestionTaskExecutor.IngestOneResult r = executor.ingestOne(
                            path.getFileName().toString(), path, collectionId, defaultCollection, i -> {});
                    if (r.chunks() > 0) {
                        recordIngestion(hash, path.getFileName().toString(), r.chunks(), defaultCollection);
                    }
                    total += r.chunks();
                } catch (Exception e) {
                    log.warn("Erreur ingestion fichier {}: {}", path, e.getMessage());
                }
            }
            return total;
        } catch (Exception e) {
            log.error("Erreur lors de l'ingestion locale: {}", e.getMessage(), e);
            return 0;
        }
    }

    // ── Ingestion streaming (Kafka) — upsert par identité métier ────────────────

    /** Résultat d'un {@link #upsertFromStream}. */
    public record UpsertResult(String sourceKey, Kind kind, int removedChunks, int indexedChunks) {
        public enum Kind { UPSERTED, UNCHANGED, DELETED }
        static UpsertResult upserted(String key, int removed, int indexed) { return new UpsertResult(key, Kind.UPSERTED, removed, indexed); }
        static UpsertResult unchanged(String key) { return new UpsertResult(key, Kind.UNCHANGED, 0, 0); }
        static UpsertResult deleted(String key, int removed) { return new UpsertResult(key, Kind.DELETED, removed, 0); }
    }

    /**
     * Upsert streaming : remplace (du point de vue métier) tous les chunks portant la clé
     * {@code sourceKey} par la version courante du contenu. Enrichit le RAG au fil de l'eau.
     *
     * <p>Contrairement à {@link #ingest}, ne fait <b>pas</b> de déduplication SHA-256 par contenu
     * (on veut remplacer, pas ignorer). Le flux est :</p>
     * <ol>
     *   <li><b>Tombstone</b> — un contenu vide (message Kafka {@code value=null}) supprime l'entrée.</li>
     *   <li><b>Idempotence</b> — si le hash du texte nettoyé est identique à la dernière version
     *       indexée, on ne fait rien (absorbe les rejeux at-least-once).</li>
     *   <li><b>Delete-then-index</b> — purge des deux index (vecteur + BM25) par {@code sourceKey},
     *       puis réindexation de la version courante.</li>
     * </ol>
     *
     * @param sourceKey    identité métier stable (ex. {@code kafka://<topic>/<key>}) — sert de sourceFile
     * @param logicalName  nom logique portant l'extension de routage (ex. {@code <sourceKey>.json})
     * @param content      flux du payload brut du message
     * @param collectionName collection ChromaDB cible (dédiée au flux)
     * @param offsetRef    référence topic/partition/offset (diagnostic), peut être {@code null}
     */
    public UpsertResult upsertFromStream(String sourceKey, String logicalName, InputStream content,
                                         String collectionName, String offsetRef) throws Exception {
        return upsertFromStream(sourceKey, logicalName, content, collectionName, offsetRef, Map.of());
    }

    /**
     * Variante acceptant des métadonnées supplémentaires (temporelles, champs mappés…) fusionnées
     * dans chaque chunk — utile pour le filtrage/tri par récence au retrieval.
     */
    public UpsertResult upsertFromStream(String sourceKey, String logicalName, InputStream content,
                                         String collectionName, String offsetRef,
                                         Map<String, String> extraMetadata) throws Exception {
        String collectionId = chromaDbClient.getOrCreateCollection(collectionName);

        // 1. Extraction → nettoyage → chunking (sourceFile = clé métier stable)
        String contentType = extractorFactory.resolveContentType(logicalName);
        var extractor = extractorFactory.getExtractor(contentType);
        ExtractedDocument doc = extractor.extract(logicalName, content);
        String cleanedText = textCleaner.clean(doc.text());

        // Fraîcheur temporelle : horodatage d'ingestion + métadonnées fournies (eventTime, champs mappés).
        Map<String, String> metadata = new java.util.HashMap<>(doc.metadata());
        metadata.put("ingestedAt", Instant.now().toString());
        if (extraMetadata != null) metadata.putAll(extraMetadata);
        List<TextChunk> chunks = chunkingService.chunk(cleanedText, sourceKey, metadata);

        // 2. Tombstone : plus de contenu → suppression pure
        if (chunks.isEmpty()) {
            int removed = purgeSource(collectionId, collectionName, sourceKey);
            streamSourceRepository.deleteById(sourceKey);
            log.info("Upsert streaming '{}' : tombstone → {} chunks supprimés", sourceKey, removed);
            return UpsertResult.deleted(sourceKey, removed);
        }

        // 3. Idempotence : contenu inchangé → no-op (rejeu)
        String contentHash = sha256(new java.io.ByteArrayInputStream(cleanedText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        StreamSourceEntity existing = streamSourceRepository.findById(sourceKey).orElse(null);
        if (existing != null && contentHash.equals(existing.getContentHash())) {
            log.debug("Upsert streaming '{}' : contenu inchangé, ignoré", sourceKey);
            return UpsertResult.unchanged(sourceKey);
        }

        // 4. Delete ancienne version (les deux index)
        int removed = purgeSource(collectionId, collectionName, sourceKey);

        // 5. Index version courante (embed par lot + ChromaDB + BM25)
        embedAndIndex(collectionId, collectionName, chunks);

        // 6. Traçabilité de l'état par clé métier
        recordUpsert(existing, sourceKey, collectionName, contentHash, chunks.size(), offsetRef);
        log.info("Upsert streaming '{}' : {} chunks (−{} ancienne version)", sourceKey, chunks.size(), removed);
        return UpsertResult.upserted(sourceKey, removed, chunks.size());
    }

    /**
     * Purge complète d'une source du flux : retire ses chunks des deux index (ChromaDB + BM25)
     * et supprime son état de suivi. Utilisé par la politique de rétention du flux Kafka.
     *
     * @return nombre de chunks supprimés côté vecteur
     */
    public int purgeStreamSource(String sourceKey, String collectionName) {
        String collectionId = chromaDbClient.getOrCreateCollection(collectionName);
        int removed = purgeSource(collectionId, collectionName, sourceKey);
        try {
            streamSourceRepository.deleteById(sourceKey);
        } catch (Exception e) {
            log.warn("Suppression état streaming '{}' échouée : {}", sourceKey, e.getMessage());
        }
        return removed;
    }

    /** Purge une source des deux index (vecteur ChromaDB + BM25). Retourne le nb de chunks supprimés côté vecteur. */
    private int purgeSource(String collectionId, String collectionName, String sourceKey) {
        int removed = 0;
        try {
            removed = chromaDbClient.deleteBySource(collectionId, sourceKey);
        } catch (Exception e) {
            log.warn("Purge ChromaDB échouée pour '{}': {}", sourceKey, e.getMessage());
        }
        try {
            ftsService.removeBySource(sourceKey, collectionName);
        } catch (Exception e) {
            log.warn("Purge BM25 échouée pour '{}': {}", sourceKey, e.getMessage());
        }
        return removed;
    }

    /** Embed les chunks par lot et les indexe dans ChromaDB puis BM25. */
    private void embedAndIndex(String collectionId, String collectionName, List<TextChunk> chunks) {
        for (int i = 0; i < chunks.size(); i += embeddingBatchSize) {
            int end = Math.min(i + embeddingBatchSize, chunks.size());
            List<TextChunk> batch = chunks.subList(i, end);
            List<List<Float>> batchEmbeddings = embeddingService.embedBatch(
                    batch.stream().map(TextChunk::text).toList());
            chromaDbClient.addDocuments(collectionId, batch, batchEmbeddings);
        }
        ftsService.indexChunks(chunks, collectionName);
    }

    private void recordUpsert(StreamSourceEntity existing, String sourceKey, String collectionName,
                              String contentHash, int chunkCount, String offsetRef) {
        try {
            StreamSourceEntity entity = existing != null ? existing : new StreamSourceEntity(sourceKey, collectionName);
            entity.setCollection(collectionName);
            entity.setContentHash(contentHash);
            entity.setChunkCount(chunkCount);
            entity.setVersion(entity.getVersion() + 1);
            entity.setLastUpdatedAt(Instant.now());
            if (offsetRef != null) entity.setLastOffsetRef(offsetRef);
            streamSourceRepository.save(entity);
        } catch (Exception e) {
            log.warn("Persistance état streaming '{}' échouée : {}", sourceKey, e.getMessage());
        }
    }

    // ── Pipeline direct ───────────────────────────────────────────────────────

    /**
     * Package-visible pour les tests — délègue à l'unique pipeline d'extraction/chunking/
     * indexation de {@link IngestionTaskExecutor}. La logique ZIP (walk récursif, bornes anti
     * ZIP-bomb, filtrage des entrées) n'est plus dupliquée ici : une seule implémentation évite
     * la dérive entre le chemin upload (asynchrone) et le chemin URL/batch.
     */
    int processZip(InputStream zipStream, String archiveName, String collectionId) throws Exception {
        return executor.ingestZip(zipStream, archiveName, collectionId, defaultCollection);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Copie un InputStream vers un fichier temp et retourne son SHA-256 hex.
     */
    private String copyAndHash(InputStream in, Path dest) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream dis = new DigestInputStream(in, digest);
             OutputStream out = Files.newOutputStream(dest)) {
            dis.transferTo(out);
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String sha256(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream dis = new DigestInputStream(in, digest)) {
            dis.transferTo(OutputStream.nullOutputStream());
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
