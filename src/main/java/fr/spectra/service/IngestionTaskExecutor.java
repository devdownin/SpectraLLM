package fr.spectra.service;

import fr.spectra.dto.IngestionTask;
import fr.spectra.model.ExtractedDocument;
import fr.spectra.model.TextChunk;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import fr.spectra.service.extraction.ExtractionException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Exécuteur asynchrone des tâches d'ingestion.
 * Séparé de IngestionService pour que @Async soit honoré par le proxy Spring.
 * Travaille sur des fichiers temp copiés avant la fin de la requête HTTP.
 */
@Service
public class IngestionTaskExecutor {

    @FunctionalInterface
    public interface IngestionCallback {
        void onIngested(String hash, String fileName, int chunksCreated);
    }

    private static final Logger log = LoggerFactory.getLogger(IngestionTaskExecutor.class);

    private final DocumentExtractorFactory extractorFactory;
    private final TextCleanerService textCleaner;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final ChromaDbClient chromaDbClient;
    private final FtsService ftsService;
    private final int embeddingBatchSize;
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
                                 @Value("${spectra.pipeline.embedding-batch-size:10}") int embeddingBatchSize) {
        this.extractorFactory = extractorFactory;
        this.textCleaner = textCleaner;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.chromaDbClient = chromaDbClient;
        this.ftsService = ftsService;
        this.embeddingBatchSize = embeddingBatchSize;
        this.chunksIngested = Counter.builder("spectra.ingestion.chunks.total")
                .description("Nombre total de chunks ingérés dans ChromaDB")
                .register(meterRegistry);
        this.filesIngested = Counter.builder("spectra.ingestion.files.total")
                .description("Nombre total de fichiers ingérés")
                .register(meterRegistry);
        this.ingestionTimer = Timer.builder("spectra.ingestion.file.duration")
                .description("Durée d'ingestion par fichier")
                .register(meterRegistry);
    }

    /**
     * Exécute l'ingestion asynchrone.
     *
     * @param tempFileToHash map de fichier temp → SHA-256 (peut être null)
     * @param onHashIngested callback appelé avec le hash après chaque ingestion réussie (peut être null)
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
        tasks.put(taskId, tasks.get(taskId).processing());
        try {
            String collectionId = chromaDbClient.getOrCreateCollection(collectionName);
            int totalChunks = 0;
            String lastParserUsed = null;
            int totalLayoutAwareChunks = 0;

            for (int i = 0; i < tempFiles.size(); i++) {
                String name = i < fileNames.size() ? fileNames.get(i) : tempFiles.get(i).getFileName().toString();
                Path currentTempFile = tempFiles.get(i);
                final int[] resultHolder = new int[1];
                final String[] parserHolder = new String[1];
                final int[] layoutHolder = new int[1];
                ingestionTimer.record(() -> {
                    try {
                        IngestOneResult r = ingestOne(name, currentTempFile, collectionId, collectionName);
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
                if (chunks > 0) { chunksIngested.increment(chunks); filesIngested.increment(); }
                if (chunks > 0 && onIngested != null) {
                    String hash = tempFileToHash != null ? tempFileToHash.get(tempFiles.get(i)) : null;
                    onIngested.onIngested(hash, name, chunks);
                }
            }
            tasks.put(taskId, tasks.get(taskId).completed(totalChunks, lastParserUsed, totalLayoutAwareChunks));
            log.info("Ingestion {} terminée: {} chunks total, parser={}", taskId, totalChunks, lastParserUsed);
        } catch (Throwable e) {
            log.error("Erreur lors de l'ingestion {}: {}", taskId, e.getMessage(), e);
            tasks.put(taskId, tasks.get(taskId).failed(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            tempFiles.forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            if (tempDir != null) {
                try { Files.deleteIfExists(tempDir); }
                catch (Exception e) { log.warn("Impossible de supprimer le répertoire temp {}: {}", tempDir, e.getMessage()); }
            }
        }
    }

    private IngestOneResult ingestOne(String fileName, Path tempFile, String collectionId, String collectionName) throws Exception {
        log.info("Ingestion de: {}", fileName);

        if (fileName.toLowerCase().endsWith(".zip")) {
            try (InputStream is = Files.newInputStream(tempFile)) {
                int chunks = ingestZip(is, fileName, collectionId, collectionName);
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

        List<List<Float>> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += embeddingBatchSize) {
            int end = Math.min(i + embeddingBatchSize, chunks.size());
            List<String> batchTexts = chunks.subList(i, end).stream()
                    .map(TextChunk::text)
                    .toList();
            allEmbeddings.addAll(embeddingService.embedBatch(batchTexts));
        }

        chromaDbClient.addDocuments(collectionId, chunks, allEmbeddings);
        ftsService.indexChunks(chunks, collectionName);
        log.info("Fichier {} traité: {} chunks, parser={}", fileName, chunks.size(), parserUsed);
        return new IngestOneResult(chunks.size(), parserUsed, layoutAware ? chunks.size() : 0);
    }

    /** Package-visible for testing. */
    int ingestZip(InputStream zipStream, String archiveName, String collectionId, String collectionName) throws Exception {
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
                    // Wrap in non-closing stream so the recursive ingestZip() won't close
                    // the parent ZipInputStream when it reaches its own try-with-resources.
                    InputStream nonClosing = new FilterInputStream(zis) {
                        @Override public void close() {}
                    };
                    totalChunks += ingestZip(nonClosing, archiveName + "/" + entryName, collectionId, collectionName);
                    continue;
                }
                if (!isSupportedFile(fileName)) continue;

                String qualifiedName = archiveName + "/" + entryName;
                try {
                    // Wrap in non-closing stream: some extractors (Jackson AUTO_CLOSE_SOURCE)
                    // close the stream after reading, which would break the ZipInputStream.
                    InputStream entryStream = new FilterInputStream(zis) {
                        @Override public void close() { /* do not close the parent ZipInputStream */ }
                    };
                    totalChunks += ingestEntry(qualifiedName, entryStream, collectionId, collectionName);
                } catch (ExtractionException e) {
                    log.warn("Erreur sur entrée ZIP {}: {}", qualifiedName, e.getMessage());
                }
            }
        }
        return totalChunks;
    }

    private int ingestEntry(String fileName, InputStream inputStream, String collectionId, String collectionName) throws Exception {
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

        List<List<Float>> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += embeddingBatchSize) {
            int end = Math.min(i + embeddingBatchSize, chunks.size());
            allEmbeddings.addAll(embeddingService.embedBatch(
                    chunks.subList(i, end).stream().map(TextChunk::text).toList()));
        }

        chromaDbClient.addDocuments(collectionId, chunks, allEmbeddings);
        ftsService.indexChunks(chunks, collectionName);
        log.info("Entrée ZIP {} traitée: {} chunks", fileName, chunks.size());
        return chunks.size();
    }

    private boolean isSupportedFile(String fileName) {
        try {
            extractorFactory.resolveContentType(fileName);
            return true;
        } catch (ExtractionException e) {
            return false;
        }
    }

    private record IngestOneResult(int chunks, String parserUsed, int layoutAwareChunks) {}
}
