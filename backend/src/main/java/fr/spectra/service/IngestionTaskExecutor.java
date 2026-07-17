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
            // Sert aussi de heartbeat : l'appelant rafraîchit ses réservations in-flight, pour
            // qu'une ingestion plus longue que le TTL ne soit pas « reprise » par un doublon.
            final int[] addedSoFar = {0};
            final IntConsumer progress = delta -> {
                final int now = (addedSoFar[0] += delta);
                tasks.computeIfPresent(taskId, (k, t) ->
                        t.status() == IngestionTask.Status.CANCELLED ? t : t.progress(now));
                if (onIngested != null) onIngested.heartbeat();
            };
            // Dénominateur live : dès qu'un fichier (ou une entrée ZIP) est découpé, son total de
            // chunks s'ajoute au « attendu » — l'UI peut afficher une barre déterminée pendant
            // la phase d'embedding, la plus longue.
            final int[] expectedSoFar = {0};
            final IntConsumer discovered = count -> {
                final int now = (expectedSoFar[0] += count);
                tasks.computeIfPresent(taskId, (k, t) ->
                        t.status() == IngestionTask.Status.CANCELLED ? t : t.expecting(now));
            };

            int failedFiles = 0;
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
                String hash = tempFileToHash != null ? tempFileToHash.get(currentTempFile) : null;
                // Ré-ingestion (force) : laisse l'appelant purger l'ancienne version des index
                // AVANT la ré-indexation — sans cela, chaque force dupliquait tous les chunks
                // du document dans ChromaDB et BM25.
                if (hash != null && onIngested != null) {
                    try {
                        onIngested.beforeIndex(hash, name);
                    } catch (Exception e) {
                        log.warn("Purge pré-réindexation échouée pour {} : {}", name, e.getMessage());
                    }
                }
                final int[] resultHolder = new int[1];
                final String[] parserHolder = new String[1];
                final int[] layoutHolder = new int[1];
                final String[] errorHolder = new String[1];
                ingestionTimer.record(() -> {
                    try {
                        IngestOneResult r = ingestOne(name, currentTempFile, collectionId, collectionName,
                                progress, discovered, hash);
                        resultHolder[0] = r.chunks();
                        parserHolder[0] = r.parserUsed();
                        layoutHolder[0] = r.layoutAwareChunks();
                    } catch (Exception e) {
                        log.error("Erreur lors de l'ingestion du fichier {}: {}", name, e.getMessage());
                        resultHolder[0] = 0;
                        errorHolder[0] = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    }
                });
                int chunks = resultHolder[0];
                // L'échec d'un fichier n'interrompt pas la tâche, mais il est désormais visible
                // dans le suivi (fileErrors) au lieu d'un COMPLETED silencieux.
                if (errorHolder[0] != null) {
                    failedFiles++;
                    final String fileError = name + ": " + errorHolder[0];
                    tasks.computeIfPresent(taskId, (k, t) -> t.withFileError(fileError));
                }
                if (parserHolder[0] != null) lastParserUsed = parserHolder[0];
                totalLayoutAwareChunks += layoutHolder[0];
                totalChunks += chunks;
                if (chunks > 0) {
                    chunksIngested.increment(chunks);
                    filesIngested.increment();
                }
                // On enregistre le document dès qu'au moins un chunk a été indexé (y compris en cas
                // d'échec partiel d'un lot tardif) : on conserve ce qui a réussi.
                if (chunks > 0 && onIngested != null) {
                    onIngested.onIngested(hash, name, chunks);
                }
            }
            final int finalChunks = totalChunks;
            final String finalParser = lastParserUsed;
            final int finalLayout = totalLayoutAwareChunks;
            final boolean allFailed = failedFiles > 0 && failedFiles == tempFiles.size();
            // Ne pas écraser un statut CANCELLED. Tous les fichiers en échec → FAILED
            // (et non plus COMPLETED avec 0 chunk, indiscernable d'un succès).
            tasks.computeIfPresent(taskId, (k, t) -> {
                if (t.status() == IngestionTask.Status.CANCELLED) return t;
                if (allFailed) {
                    return t.failed("Aucun fichier n'a pu être ingéré : "
                            + String.join(" ; ", t.fileErrors()));
                }
                return t.completed(finalChunks, finalParser, finalLayout);
            });
            log.info("Ingestion {} terminée: {} chunks total, {} fichier(s) en échec, parser={}",
                    taskId, totalChunks, failedFiles, lastParserUsed);
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
            if (onIngested != null) {
                try { onIngested.onFinished(); } catch (Exception ignored) {}
            }
        }
    }

    /** Ingère un seul fichier local (route les .zip vers {@link #ingestZip}). Package-visible :
     *  réutilisé par {@link IngestionService} (URL / batch) pour éviter un pipeline dupliqué. */
    IngestOneResult ingestOne(String fileName, Path tempFile, String collectionId, String collectionName,
                                      IntConsumer progress) throws Exception {
        return ingestOne(fileName, tempFile, collectionId, collectionName, progress, NOOP_PROGRESS, null);
    }

    IngestOneResult ingestOne(String fileName, Path tempFile, String collectionId, String collectionName,
                                      IntConsumer progress, IntConsumer discovered) throws Exception {
        return ingestOne(fileName, tempFile, collectionId, collectionName, progress, discovered, null);
    }

    /**
     * Variante qui acquiert un slot du sémaphore de concurrence — pour les chemins qui
     * n'entrent pas par {@link #execute} (ingestion URL, batch local) : sans cela, N requêtes
     * simultanées lançaient N pipelines d'extraction/embedding en parallèle, hors de tout
     * plafond {@code concurrent-ingestions}.
     */
    public IngestOneResult ingestOneWithPermit(String fileName, Path tempFile, String collectionId,
                                               String collectionName, IntConsumer progress,
                                               String sha256) throws Exception {
        concurrencySemaphore.acquire();
        try {
            return ingestOne(fileName, tempFile, collectionId, collectionName, progress, NOOP_PROGRESS, sha256);
        } finally {
            concurrencySemaphore.release();
        }
    }

    /**
     * @param discovered notifié du nombre total de chunks d'un fichier dès le découpage terminé
     *   (avant l'embedding) — alimente {@link IngestionTask#chunksExpected()} pour la progression.
     * @param sha256 hachage du contenu, ajouté aux métadonnées de chaque chunk : c'est
     *   l'identité utilisée pour la suppression/le remplacement (les noms de fichiers ne sont
     *   pas uniques — deux documents homonymes ne doivent plus partager leur sort). Nullable
     *   (flux streaming Kafka, qui a sa propre identité {@code sourceKey}).
     */
    IngestOneResult ingestOne(String fileName, Path tempFile, String collectionId, String collectionName,
                                      IntConsumer progress, IntConsumer discovered, String sha256) throws Exception {
        log.info("Ingestion de: {}", fileName);

        if (fileName.toLowerCase().endsWith(".zip")) {
            try (InputStream is = Files.newInputStream(tempFile)) {
                int chunks = ingestZip(is, fileName, fileName, collectionId, collectionName, 0, progress, discovered, sha256);
                // L'archive est enregistrée comme une unité (dédup au niveau archive).
                return new IngestOneResult(chunks, null, 0, true);
            }
        }

        // Même garde-fou mémoire que les entrées ZIP et le batch local : la limite
        // décompressée ne s'appliquait pas aux uploads directs ni aux URLs, alors que la
        // plupart des extracteurs chargent tout le contenu en mémoire (readAllBytes).
        long fileSize = Files.size(tempFile);
        if (fileSize > maxEntryUncompressedBytes) {
            throw new ExtractionException("Fichier trop volumineux (" + fileSize / (1024 * 1024)
                    + " Mo > limite " + maxEntryUncompressedBytes / (1024 * 1024)
                    + " Mo) : " + fileName);
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

        Map<String, String> meta = new java.util.HashMap<>(doc.metadata() != null ? doc.metadata() : Map.of());
        if (sha256 != null) {
            meta.put("sha256", sha256);
        }
        List<TextChunk> chunks = chunkingService.chunk(cleanedText, fileName, meta);

        if (chunks.isEmpty()) {
            log.warn("Aucun chunk produit pour: {}", fileName);
            return new IngestOneResult(0, parserUsed, 0, true);
        }
        discovered.accept(chunks.size());

        // Embed + envoie à ChromaDB par lot : on n'accumule jamais toutes les
        // embeddings ni un gros payload d'ajout en mémoire (réduit le pic mémoire).
        // On compte les chunks RÉELLEMENT indexés : en cas d'échec d'un lot tardif
        // (embedding/ChromaDB indisponible), le document est tout de même enregistré
        // pour ce qui a réussi, et le compteur final reste cohérent avec la
        // progression live (pas de retour à 0 en fin d'ingestion).
        int embedded = embedAndStore(chunks, collectionId, collectionName, fileName, progress);

        if (embedded == 0) {
            log.warn("Aucun chunk indexé pour: {}", fileName);
            return new IngestOneResult(0, parserUsed, 0, false);
        }

        boolean complete = embedded == chunks.size();
        log.info("Fichier {} traité: {} chunks{}, parser={}",
                fileName, embedded, complete ? "" : "/" + chunks.size() + " (partiel)", parserUsed);
        return new IngestOneResult(embedded, parserUsed, layoutAware ? embedded : 0, complete);
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

    /** Surcharge (utilisée par les tests) : la source racine par défaut est le nom d'archive. */
    int ingestZip(InputStream zipStream, String archiveName, String collectionId,
                  String collectionName, int depth, IntConsumer progress) throws Exception {
        return ingestZip(zipStream, archiveName, archiveName, collectionId, collectionName, depth, progress, NOOP_PROGRESS, null);
    }

    /**
     * @param rootSource identité stable de l'archive telle qu'enregistrée en GED (nom de l'upload).
     *   Toutes les entrées (y compris imbriquées) portent CETTE {@code sourceFile}, afin que la
     *   suppression GED ({@code deleteBySource(rootSource)}) et la purge BM25 retrouvent bien leurs
     *   chunks. Le chemin réel de l'entrée est conservé dans la métadonnée {@code zipEntry}.
     * @param sha256 hachage de l'archive racine, propagé inchangé aux entrées (même identité GED).
     */
    int ingestZip(InputStream zipStream, String archiveName, String rootSource, String collectionId,
                  String collectionName, int depth, IntConsumer progress, IntConsumer discovered,
                  String sha256) throws Exception {
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
                String fileName = entryName.contains("/")
                        ? entryName.substring(entryName.lastIndexOf('/') + 1)
                        : entryName;
                // Ignorer les métadonnées macOS et les fichiers cachés (basename commençant par
                // "."), MAIS pas les entrées normales préfixées "./" (fréquent avec `zip -r`),
                // qui étaient auparavant écartées à tort → perte silencieuse de documents.
                if (entryName.startsWith("__MACOSX/") || fileName.isEmpty() || fileName.startsWith(".")) continue;
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

                if (fileName.toLowerCase().endsWith(".zip")) {
                    // Flux non-fermable : la récursion ne doit pas fermer le ZipInputStream parent.
                    // rootSource est propagé inchangé : les chunks imbriqués restent rattachés à l'archive.
                    InputStream nonClosing = new LimitedInputStream(new FilterInputStream(zis) {
                        @Override public void close() {}
                    }, maxEntryUncompressedBytes);
                    totalChunks += ingestZip(nonClosing, archiveName + "/" + entryName, rootSource,
                            collectionId, collectionName, depth + 1, progress, discovered, sha256);
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
                    totalChunks += ingestEntry(qualifiedName, rootSource, entryStream, collectionId,
                            collectionName, progress, discovered, sha256);
                } catch (ExtractionException e) {
                    log.warn("Erreur sur entrée ZIP {}: {}", qualifiedName, e.getMessage());
                }
            }
        }
        return totalChunks;
    }

    /**
     * @param rootSource {@code sourceFile} rattaché aux chunks (l'archive, pour que la suppression
     *   par source fonctionne) ; {@code fileName} est le chemin qualifié conservé dans la métadonnée
     *   {@code zipEntry} pour la traçabilité de la provenance.
     */
    private int ingestEntry(String fileName, String rootSource, InputStream inputStream,
                            String collectionId, String collectionName, IntConsumer progress,
                            IntConsumer discovered, String sha256) throws Exception {
        String shortName = fileName.contains("/") ? fileName.substring(fileName.lastIndexOf('/') + 1) : fileName;
        String contentType = extractorFactory.resolveContentType(shortName);
        var extractor = extractorFactory.getExtractor(contentType);
        ExtractedDocument doc = extractor.extract(fileName, inputStream);

        String cleanedText = textCleaner.clean(doc.text());
        // Rattacher les chunks à l'archive (rootSource) pour une suppression cohérente, tout en
        // gardant le chemin réel de l'entrée dans les métadonnées.
        Map<String, String> meta = new java.util.HashMap<>(doc.metadata() != null ? doc.metadata() : Map.of());
        meta.put("zipEntry", fileName);
        if (sha256 != null) {
            meta.put("sha256", sha256);
        }
        List<TextChunk> chunks = chunkingService.chunk(cleanedText, rootSource, meta);

        if (chunks.isEmpty()) {
            log.warn("Aucun chunk produit pour l'entrée ZIP: {}", fileName);
            return 0;
        }
        discovered.accept(chunks.size());

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

    record IngestOneResult(int chunks, String parserUsed, int layoutAwareChunks, boolean complete) {}

    public interface IngestionCallback {
        void onIngested(String hash, String fileName, int chunks);

        /**
         * Appelé juste avant l'indexation d'un fichier dont le hachage est connu. Permet à
         * l'appelant de purger l'ancienne version des index (ChromaDB + BM25) quand le
         * document existe déjà en GED (ré-ingestion {@code force=true}) — le remplacement
         * suit ainsi la même sémantique delete-then-index que le flux streaming, au lieu
         * d'empiler des chunks dupliqués.
         */
        default void beforeIndex(String hash, String fileName) {}

        /**
         * Battement de cœur émis à chaque lot d'embeddings indexé : l'appelant peut y
         * rafraîchir ses réservations in-flight pour qu'elles survivent aux ingestions
         * plus longues que leur TTL.
         */
        default void heartbeat() {}

        /**
         * Appelé une fois la tâche terminée (succès, échec partiel ou total). Permet à
         * l'appelant de libérer immédiatement ses réservations (hash in-flight) pour les
         * fichiers qui ont ÉCHOUÉ — sans ce signal, une ré-ingestion du même contenu
         * restait bloquée jusqu'à l'expiration du TTL (15 min).
         */
        default void onFinished() {}
    }
}
