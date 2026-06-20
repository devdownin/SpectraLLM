package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.IngestionTask;
import fr.spectra.model.ExtractedDocument;
import fr.spectra.model.TextChunk;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import fr.spectra.service.extraction.ExtractionException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Exécuteur asynchrone pour les tâches d'ingestion.
 * Gère la concurrence, l'extraction, le chunking, l'embedding et le stockage.
 */
@Service
public class IngestionTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(IngestionTaskExecutor.class);
    /** Même limite que IngestionService.MAX_ZIP_DEPTH — protège contre les ZIP bombs imbriqués. */
    private static final int MAX_ZIP_DEPTH = 3;
    private static final IntConsumer NOOP_PROGRESS = i -> {};

    private final DocumentExtractorFactory extractorFactory;
    private final TextCleanerService textCleaner;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final ChromaDbClient chromaDbClient;
    private final FtsService ftsService;
    private final int embeddingBatchSize;
    /** Taille décompressée maximale autorisée par fichier/entrée (mémoire + anti-ZIP-bomb). */
    private final long maxEntryUncompressedBytes;
    /** Nombre maximal d'entrées traitées par archive (protection ZIP bomb). */
    private final int maxZipEntries;
    private final Semaphore concurrencySemaphore;
    private final Counter chunksIngested;
    private final Counter filesIngested;
    private final Timer ingestionTimer;

    public IngestionTaskExecutor(DocumentExtractorFactory extractorFactory,
                                 TextCleanerService textCleaner,
                                 ChunkingService chunkingService,
                                 EmbeddingService embeddingService,
                                 ChromaDbClient chromaDbClient,
                                 FtsService ftsService,
                                 MeterRegistry meterRegistry,
                                 SpectraProperties properties,
                                 @Value("${spectra.pipeline.embedding-batch-size:10}") int embeddingBatchSize,
                                 @Value("${spectra.pipeline.max-uncompressed-mb:0}") int maxUncompressedMb,
                                 @Value("${spectra.pipeline.concurrent-ingestions:4}") int concurrentIngestions) {
        this.extractorFactory = extractorFactory;
        this.textCleaner = textCleaner;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.chromaDbClient = chromaDbClient;
        this.ftsService = ftsService;
        this.embeddingBatchSize = embeddingBatchSize;
        this.maxZipEntries = properties.ingestion() != null ? properties.ingestion().effectiveMaxZipEntries() : 10_000;
        // 0 → auto-calcul selon le heap et la concurrence (évite l'OOM).
        this.maxEntryUncompressedBytes = IngestionLimits.resolveMaxUncompressedBytes(maxUncompressedMb, concurrentIngestions);
        this.concurrencySemaphore = new Semaphore(Math.max(1, concurrentIngestions), true);
        log.info("[ingestion] Limite de concurrence : {} ; taille décompressée max/fichier : {} Mo ({})",
                concurrentIngestions, maxEntryUncompressedBytes / (1024 * 1024),
                maxUncompressedMb > 0 ? "explicite" : "auto");
        this.chunksIngested = Counter.builder("spectra.ingestion.chunks.total")
                .description("Nombre total de chunks ingérés dans ChromaDB")
                .register(meterRegistry);
        this.filesIngested = Counter.builder("spectra.ingestion.files.total")
                .description("Nombre total de fichiers ingérés")
                .register(meterRegistry);
        this.ingestionTimer = Timer.builder("spectra.ingestion.file.duration")
                .description("Durée d'ingestion par fichier")
                .register(meterRegistry);
        // Profondeur de file / capacité disponible — détecte la saturation du pipeline d'ingestion.
        meterRegistry.gauge("spectra.ingestion.concurrency.available", concurrencySemaphore, Semaphore::availablePermits);
        meterRegistry.gauge("spectra.ingestion.concurrency.queued", concurrencySemaphore, s -> (double) s.getQueueLength());
    }

    /**
     * Exécute l'ingestion asynchrone.
     *
     * @param tempFileToHash map de fichier temp → SHA-256 (peut être null)
     * @param onIngested     callback appelé avec le hash après chaque ingestion réussie (peut être null)
     */
    @Async
    public void execute(String taskId, List<String> fileNames, List<Path> tempFiles,
                        Map<String, IngestionTask> tasks, String collectionName,
                        Map<Path, String> tempFileToHash, IngestionCallback onIngested) {
        execute(taskId, fileNames, tempFiles, tasks, collectionName, tempFileToHash, onIngested, null);
    }

    @Async
    public void execute(String taskId, List<String> fileNames, List<Path> tempFiles,
                        Map<String, IngestionTask> tasks, String collectionName,
                        Map<Path, String> tempFileToHash, IngestionCallback onIngested,
                        Path tempDir) {
        try {
            concurrencySemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tasks.computeIfPresent(taskId, (k, t) -> t.failed("Ingestion annulée (attente de slot interrompue)"));
            return;
        }
        // Ne pas écraser un statut CANCELLED positionné avant le démarrage effectif.
        tasks.computeIfPresent(taskId, (k, t) ->
                t.status() == IngestionTask.Status.CANCELLED ? t : t.processing());
        try {
            String collectionId = chromaDbClient.getOrCreateCollection(collectionName);
            int totalChunks = 0;
            String lastParserUsed = null;
            int totalLayoutAwareChunks = 0;

            // Progression live : chaque lot d'embeddings ajouté incrémente le compteur de la
            // tâche, immédiatement visible via le polling de l'UI — y compris pour un seul fichier.
            final int[] addedSoFar = {0};
            final IntConsumer progress = delta -> {
                final int now = (addedSoFar[0] += delta);
                tasks.computeIfPresent(taskId, (k, t) ->
                        t.status() == IngestionTask.Status.CANCELLED ? t : t.progress(now));
            };

            for (int i = 0; i < tempFiles.size(); i++) {
                // Point de contrôle d'annulation : interrompt proprement la boucle.
                IngestionTask current = tasks.get(taskId);
                if (current != null && current.status() == IngestionTask.Status.CANCELLED) {
                    log.info("Ingestion {} annulée — arrêt après {} fichier(s) traité(s)", taskId, i);
                    return;
                }
                String declaredName = i < fileNames.size() ? fileNames.get(i) : null;
                String name = declaredName != null ? declaredName : tempFiles.get(i).getFileName().toString();
                Path currentTempFile = tempFiles.get(i);
                final int[] resultHolder = new int[1];
                final String[] parserHolder = new String[1];
                final int[] layoutHolder = new int[1];
                ingestionTimer.record(() -> {
                    try {
                        IngestOneResult r = ingestOne(name, currentTempFile, collectionId, collectionName, progress);
                        resultHolder[0] = r.chunks();
                        parserHolder[0] = r.parserUsed();
                        layoutHolder[0] = r.layoutAwareChunks();
                    } catch (Exception e) {
                        log.error("Erreur lors de l'ingestion du fichier {}: {}", name, e.getMessage());
                        resultHolder[0] = 0;
                    }
                });
                int chunks = resultHolder[0];
                if (parserHolder[0] != null) lastParserUsed = parserHolder[0];
                totalLayoutAwareChunks += layoutHolder[0];
                totalChunks += chunks;
                if (chunks > 0) {
                    chunksIngested.increment(chunks);
                    filesIngested.increment();
                }
                if (chunks > 0 && onIngested != null) {
                    String hash = tempFileToHash != null ? tempFileToHash.get(tempFiles.get(i)) : null;
                    onIngested.onIngested(hash, name, chunks);
                }
            }
            final int finalChunks = totalChunks;
            final String finalParser = lastParserUsed;
            final int finalLayout = totalLayoutAwareChunks;
            // Ne pas écraser un statut CANCELLED par COMPLETED.
            tasks.computeIfPresent(taskId, (k, t) ->
                    t.status() == IngestionTask.Status.CANCELLED ? t : t.completed(finalChunks, finalParser, finalLayout));
            log.info("Ingestion {} terminée: {} chunks total, parser={}", taskId, totalChunks, lastParserUsed);
        } catch (OutOfMemoryError e) {
            // Convertit un OOM en échec de tâche explicite plutôt que de laisser mourir le thread
            // d'ingestion : le semaphore est libéré dans le finally, le service reste opérationnel.
            log.error("ERREUR CRITIQUE : mémoire saturée (OOM) lors de l'ingestion {}. "
                    + "Fichier trop volumineux ou concurrence trop élevée pour le heap configuré.", taskId);
            tasks.computeIfPresent(taskId, (k, t) ->
                    t.failed("Erreur mémoire (OOM) : document trop volumineux pour la configuration mémoire actuelle."));
        } catch (Throwable e) {
            log.error("Erreur lors de l'ingestion {}: {}", taskId, e.getMessage(), e);
            tasks.computeIfPresent(taskId, (k, t) -> t.failed(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            concurrencySemaphore.release();
            tempFiles.forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            if (tempDir != null) {
                try { Files.deleteIfExists(tempDir); }
                catch (Exception e) { log.warn("Impossible de supprimer le répertoire temp {}: {}", tempDir, e.getMessage()); }
            }
        }
    }

    private IngestOneResult ingestOne(String fileName, Path tempFile, String collectionId, String collectionName,
                                      IntConsumer progress) throws Exception {
        log.info("Ingestion de: {}", fileName);

        if (fileName.toLowerCase().endsWith(".zip")) {
            try (InputStream is = Files.newInputStream(tempFile)) {
                int chunks = ingestZip(is, fileName, collectionId, collectionName, 0, progress);
                return new IngestOneResult(chunks, null, 0);
            }
        }

        String contentType = extractorFactory.resolveContentType(fileName);
        var extractor = extractorFactory.getExtractor(contentType);

        ExtractedDocument doc;
        try (InputStream is = Files.newInputStream(tempFile)) {
            doc = extractor.extract(fileName, is);
        }

        String parserUsed = doc.metadata() != null ? doc.metadata().get("parser") : null;
        boolean layoutAware = "true".equals(doc.metadata() != null ? doc.metadata().get("layoutAware") : null);

        String cleanedText = textCleaner.clean(doc.text());

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        log.debug("Avant chunking '{}': textLen={} chars, heap used={}MB / max={}MB",
                fileName, cleanedText.length(), usedMb, rt.maxMemory() / 1024 / 1024);

        List<TextChunk> chunks = chunkingService.chunk(cleanedText, fileName, doc.metadata());

        if (chunks.isEmpty()) {
            log.warn("Aucun chunk produit pour: {}", fileName);
            return new IngestOneResult(0, parserUsed, 0);
        }

        // Embed + envoie à ChromaDB par lot : on n'accumule jamais toutes les
        // embeddings ni un gros payload d'ajout en mémoire (réduit le pic mémoire).
        // On compte les chunks RÉELLEMENT indexés : en cas d'échec d'un lot tardif
        // (embedding/ChromaDB indisponible), le document est tout de même enregistré
        // pour ce qui a réussi, et le compteur final reste cohérent avec la
        // progression live (pas de retour à 0 en fin d'ingestion).
        int embedded = embedAndStore(chunks, collectionId, collectionName, fileName, progress);

        if (embedded == 0) {
            log.warn("Aucun chunk indexé pour: {}", fileName);
            return new IngestOneResult(0, parserUsed, 0);
        }

        log.info("Fichier {} traité: {} chunks{}, parser={}",
                fileName, embedded, embedded < chunks.size() ? "/" + chunks.size() + " (partiel)" : "", parserUsed);
        return new IngestOneResult(embedded, parserUsed, layoutAware ? embedded : 0);
    }

    /**
     * Embarque et stocke les chunks par lot. Indexe chaque lot dans ChromaDB + FTS au
     * fur et à mesure et retourne le nombre de chunks effectivement indexés. Un échec de
     * lot interrompt le traitement du fichier mais conserve les chunks déjà indexés.
     */
    private int embedAndStore(List<TextChunk> chunks, String collectionId, String collectionName,
                              String fileName, IntConsumer progress) {
        int embedded = 0;
        for (int i = 0; i < chunks.size(); i += embeddingBatchSize) {
            int end = Math.min(i + embeddingBatchSize, chunks.size());
            List<TextChunk> batch = chunks.subList(i, end);
            try {
                List<List<Float>> batchEmbeddings = embeddingService.embedBatch(
                        batch.stream().map(TextChunk::text).toList());
                chromaDbClient.addDocuments(collectionId, batch, batchEmbeddings);
                ftsService.indexChunks(batch, collectionName);
                embedded += batch.size();
                progress.accept(batch.size());
            } catch (Exception e) {
                log.error("Échec du lot d'embedding [{}-{}] pour '{}' : {}. {} chunk(s) déjà indexé(s) conservé(s).",
                        i, end, fileName, e.getMessage(), embedded);
                break;
            }
        }
        return embedded;
    }

    /** Package-visible for testing. */
    int ingestZip(InputStream zipStream, String archiveName, String collectionId, String collectionName) throws Exception {
        return ingestZip(zipStream, archiveName, collectionId, collectionName, 0, NOOP_PROGRESS);
    }

    int ingestZip(InputStream zipStream, String archiveName, String collectionId,
                  String collectionName, int depth, IntConsumer progress) throws Exception {
        if (depth >= MAX_ZIP_DEPTH) {
            log.warn("Profondeur ZIP max ({}) atteinte — archive imbriquée ignorée: {}", MAX_ZIP_DEPTH, archiveName);
            return 0;
        }
        int totalChunks = 0;
        int entryCount = 0;
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (++entryCount > maxZipEntries) {
                    log.warn("Nombre max d'entrées ZIP ({}) atteint — archive tronquée: {}", maxZipEntries, archiveName);
                    break;
                }
                String entryName = entry.getName();
                if (entryName.startsWith("__MACOSX/") || entryName.startsWith(".")) continue;
                if (entryName.contains("..")) {
                    log.warn("Entrée ZIP suspecte ignorée (path traversal): {}", entryName);
                    continue;
                }
                // Pré-filtre : rejeter une entrée dont la taille décompressée déclarée est démesurée.
                if (entry.getSize() > maxEntryUncompressedBytes) {
                    log.warn("Entrée ZIP ignorée (taille décompressée {} > {} octets): {}",
                            entry.getSize(), maxEntryUncompressedBytes, entryName);
                    continue;
                }

                String fileName = entryName.contains("/")
                        ? entryName.substring(entryName.lastIndexOf('/') + 1)
                        : entryName;

                if (fileName.toLowerCase().endsWith(".zip")) {
                    // Flux non-fermable : la récursion ne doit pas fermer le ZipInputStream parent.
                    InputStream nonClosing = new LimitedInputStream(new FilterInputStream(zis) {
                        @Override public void close() {}
                    }, maxEntryUncompressedBytes);
                    totalChunks += ingestZip(nonClosing, archiveName + "/" + entryName,
                            collectionId, collectionName, depth + 1, progress);
                    continue;
                }
                if (!isSupportedFile(fileName)) {
                    continue;
                }

                String qualifiedName = archiveName + "/" + entryName;
                try {
                    // Flux non-fermable + borné : certains extracteurs (Jackson AUTO_CLOSE_SOURCE)
                    // ferment le stream après lecture, ce qui invaliderait le ZipInputStream ;
                    // la borne protège contre une entrée décompressée surdimensionnée (ZIP bomb).
                    InputStream entryStream = new LimitedInputStream(new FilterInputStream(zis) {
                        @Override public void close() { /* ne pas fermer le ZipInputStream parent */ }
                    }, maxEntryUncompressedBytes);
                    totalChunks += ingestEntry(qualifiedName, entryStream, collectionId, collectionName, progress);
                } catch (ExtractionException e) {
                    log.warn("Erreur sur entrée ZIP {}: {}", qualifiedName, e.getMessage());
                }
            }
        }
        return totalChunks;
    }

    private int ingestEntry(String fileName, InputStream inputStream, String collectionId, String collectionName,
                            IntConsumer progress) throws Exception {
        String shortName = fileName.contains("/") ? fileName.substring(fileName.lastIndexOf('/') + 1) : fileName;
        String contentType = extractorFactory.resolveContentType(shortName);
        var extractor = extractorFactory.getExtractor(contentType);
        ExtractedDocument doc = extractor.extract(fileName, inputStream);

        String cleanedText = textCleaner.clean(doc.text());
        List<TextChunk> chunks = chunkingService.chunk(cleanedText, fileName, doc.metadata());

        if (chunks.isEmpty()) {
            log.warn("Aucun chunk produit pour l'entrée ZIP: {}", fileName);
            return 0;
        }

        int embedded = embedAndStore(chunks, collectionId, collectionName, fileName, progress);
        log.info("Entrée ZIP {} traitée: {} chunks{}", fileName, embedded,
                embedded < chunks.size() ? "/" + chunks.size() + " (partiel)" : "");
        return embedded;
    }

    private boolean isSupportedFile(String fileName) {
        try {
            if (fileName != null && fileName.toLowerCase().endsWith(".zip")) {
                return true;
            }
            extractorFactory.resolveContentType(fileName);
            return true;
        } catch (ExtractionException e) {
            return false;
        }
    }

    private record IngestOneResult(int chunks, String parserUsed, int layoutAwareChunks) {}

    public interface IngestionCallback {
        void onIngested(String hash, String fileName, int chunks);
    }
}
