package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.IngestionTask;
import fr.spectra.model.ExtractedDocument;
import fr.spectra.model.TextChunk;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import fr.spectra.service.extraction.ExtractionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final String DEFAULT_COLLECTION = "spectra_documents";

    // Direct-pipeline deps (used by ingest() and ingestLocalFiles())
    private final DocumentExtractorFactory extractorFactory;
    private final TextCleanerService textCleaner;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final ChromaDbClient chromaDbClient;
    private final int embeddingBatchSize;

    // Async + dedup deps (used by submit())
    private final IngestionTaskExecutor executor;
    private final IngestedFileRepository repository;
    private final GedService gedService;
    private final String defaultCollection;
    private final double autoQualifyThreshold;

    private final Map<String, IngestionTask> tasks = new ConcurrentHashMap<>();

    public IngestionService(DocumentExtractorFactory extractorFactory,
                            TextCleanerService textCleaner,
                            ChunkingService chunkingService,
                            EmbeddingService embeddingService,
                            ChromaDbClient chromaDbClient,
                            IngestionTaskExecutor executor,
                            IngestedFileRepository repository,
                            GedService gedService,
                            SpectraProperties properties) {
        this.extractorFactory = extractorFactory;
        this.textCleaner = textCleaner;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.chromaDbClient = chromaDbClient;
        this.executor = executor;
        this.repository = repository;
        this.gedService = gedService;
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

        for (MultipartFile file : files) {
            String fileName = file.getOriginalFilename();
            try {
                Path tempFile = tempDir.resolve(UUID.randomUUID() + "_" + (fileName != null ? fileName : "unknown"));
                String hash = copyAndHash(file.getInputStream(), tempFile);

                if (!force && repository.existsById(hash)) {
                    log.info("Fichier ignoré (déjà ingéré, sha256={}): {}", hash, fileName);
                    Files.deleteIfExists(tempFile);
                    continue;
                }

                tempFiles.add(tempFile);
                tempFileToHash.put(tempFile, hash);
                toProcessNames.add(fileName);

            } catch (Exception e) {
                log.warn("Erreur préparation fichier {}: {}", fileName, e.getMessage());
            }
        }

        if (tempFiles.isEmpty()) {
            log.info("Tous les fichiers déjà ingérés — tâche {} terminée immédiatement", taskId);
            tasks.put(taskId, IngestionTask.pending(taskId, allFileNames).completed(0));
            try { Files.delete(tempDir); } catch (Exception ignored) {}
            return taskId;
        }

        tasks.put(taskId, IngestionTask.pending(taskId, allFileNames));
        executor.execute(taskId, toProcessNames, tempFiles, tasks, defaultCollection, tempFileToHash,
                (hash, fileName, chunks) -> recordIngestion(hash, fileName, chunks), tempDir);

        return taskId;
    }

    /** Compatibilité ascendante — soumet sans forcer la ré-ingestion. */
    public String submit(List<MultipartFile> files) {
        return submit(files, false);
    }

    public IngestionTask getTask(String taskId) {
        return tasks.get(taskId);
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
     */
    public int ingest(String fileName, InputStream inputStream, String collectionId) throws Exception {
        return processSingleFile(fileName, inputStream, collectionId);
    }

    /**
     * Ingère une liste de fichiers locaux de façon synchrone (utilisé par BatchService).
     */
    public int ingestLocalFiles(List<Path> paths) {
        try {
            String collectionId = chromaDbClient.getOrCreateCollection(defaultCollection);
            int total = 0;
            for (Path path : paths) {
                try (InputStream in = Files.newInputStream(path)) {
                    total += processSingleFile(path.getFileName().toString(), in, collectionId);
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

    // ── Pipeline direct ───────────────────────────────────────────────────────

    private int processSingleFile(String fileName, InputStream inputStream, String collectionId) throws Exception {
        log.info("Ingestion de: {}", fileName);

        String shortName = fileName.contains("/")
                ? fileName.substring(fileName.lastIndexOf('/') + 1)
                : fileName;

        String contentType = extractorFactory.resolveContentType(shortName);
        var extractor = extractorFactory.getExtractor(contentType);
        ExtractedDocument doc = extractor.extract(fileName, inputStream);

        String cleanedText = textCleaner.clean(doc.text());
        List<TextChunk> chunks = chunkingService.chunk(cleanedText, fileName, doc.metadata());

        if (chunks.isEmpty()) {
            log.warn("Aucun chunk produit pour: {}", fileName);
            return 0;
        }

        List<List<Float>> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += embeddingBatchSize) {
            int end = Math.min(i + embeddingBatchSize, chunks.size());
            allEmbeddings.addAll(embeddingService.embedBatch(
                    chunks.subList(i, end).stream().map(TextChunk::text).toList()));
        }

        chromaDbClient.addDocuments(collectionId, chunks, allEmbeddings);
        log.info("Fichier {} traité: {} chunks", fileName, chunks.size());
        return chunks.size();
    }

    /** Package-visible for testing. */
    int processZip(InputStream zipStream, String archiveName, String collectionId) throws Exception {
        int totalChunks = 0;
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String entryName = entry.getName();
                if (entryName.startsWith("__MACOSX/") || entryName.startsWith(".")) continue;

                String fileName = entryName.contains("/")
                        ? entryName.substring(entryName.lastIndexOf('/') + 1)
                        : entryName;

                if (fileName.toLowerCase().endsWith(".zip")) {
                    InputStream nonClosing = new java.io.FilterInputStream(zis) {
                        @Override public void close() {}
                    };
                    totalChunks += processZip(nonClosing, archiveName + "/" + entryName, collectionId);
                    continue;
                }
                if (!isSupportedFile(fileName)) continue;

                String qualifiedName = archiveName + "/" + entryName;
                try {
                    // Enveloppe zis dans un flux non-fermable : certains extracteurs
                    // (Jackson AUTO_CLOSE_SOURCE) ferment le stream après lecture,
                    // ce qui invaliderait le ZipInputStream pour les entrées suivantes.
                    InputStream entryStream = new java.io.FilterInputStream(zis) {
                        @Override public void close() { /* ne pas fermer le ZipInputStream parent */ }
                    };
                    totalChunks += processSingleFile(qualifiedName, entryStream, collectionId);
                } catch (ExtractionException e) {
                    log.warn("Erreur sur {}: {}", qualifiedName, e.getMessage());
                }
            }
        }
        return totalChunks;
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

    private boolean isSupportedFile(String fileName) {
        try {
            extractorFactory.resolveContentType(fileName);
            return true;
        } catch (ExtractionException e) {
            return false;
        }
    }
}
