package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Réconciliation périodique de cohérence entre les trois sources de vérité de
 * l'ingestion : base relationnelle (DB), ChromaDB (vecteurs) et index FTS (BM25).
 *
 * <p>Les écritures d'ingestion ne sont pas transactionnelles entre ces systèmes :
 * un échec partiel peut laisser des divergences. Ce service les détecte, expose
 * des métriques (Prometheus) et tente une réparation légère de l'index FTS
 * (reconstruction depuis ChromaDB) quand il est vide alors que des chunks existent.</p>
 */
@Service
public class ConsistencyReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyReconciliationService.class);

    private final IngestedFileRepository fileRepo;
    private final ChromaDbClient chromaDbClient;
    private final FtsService ftsService;
    private final String defaultCollection;

    // Valeurs exposées en gauges Prometheus.
    private final AtomicLong chromaChunks    = new AtomicLong(-1);
    private final AtomicLong ftsChunks       = new AtomicLong(-1);
    private final AtomicLong dbChunks        = new AtomicLong(-1);
    private final AtomicLong ftsChromaGap    = new AtomicLong(0);

    public ConsistencyReconciliationService(IngestedFileRepository fileRepo,
                                            ChromaDbClient chromaDbClient,
                                            FtsService ftsService,
                                            SpectraProperties properties,
                                            MeterRegistry meterRegistry) {
        this.fileRepo = fileRepo;
        this.chromaDbClient = chromaDbClient;
        this.ftsService = ftsService;
        this.defaultCollection = properties.chromadb() != null
                ? properties.chromadb().effectiveCollection() : "spectra_documents";

        meterRegistry.gauge("spectra.consistency.chroma.chunks", chromaChunks, AtomicLong::get);
        meterRegistry.gauge("spectra.consistency.fts.chunks", ftsChunks, AtomicLong::get);
        meterRegistry.gauge("spectra.consistency.db.chunks", dbChunks, AtomicLong::get);
        meterRegistry.gauge("spectra.consistency.fts_chroma_divergence", ftsChromaGap, AtomicLong::get);
    }

    /** Vérifie la cohérence toutes les heures (après un délai de démarrage). */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void reconcile() {
        try {
            String collectionId = chromaDbClient.getOrCreateCollection(defaultCollection);
            long chroma = chromaDbClient.count(collectionId);
            long fts    = ftsService.indexedCount(defaultCollection);
            Long db     = fileRepo.sumChunks();

            chromaChunks.set(chroma);
            ftsChunks.set(fts);
            dbChunks.set(db != null ? db : 0L);
            long gap = Math.abs(chroma - fts);
            ftsChromaGap.set(gap);

            if (gap > 0) {
                log.warn("[reconciliation] divergence FTS/ChromaDB sur '{}' : chroma={}, fts={}, db={}",
                        defaultCollection, chroma, fts, db);
            } else {
                log.debug("[reconciliation] cohérent : chroma={}, fts={}, db={}", chroma, fts, db);
            }

            // Réparation : toute divergence FTS/ChromaDB déclenche une reconstruction de
            // l'index BM25 depuis ChromaDB (source de vérité) — y compris un index FTS
            // périmé non vide (chunks fantômes après un reset de ChromaDB, ajouts perdus
            // par un arrêt brutal dans la fenêtre de flush différé…). La reconstruction
            // est sûre pendant une ingestion : les mutations concurrentes sont répliquées
            // dans l'index cible (cf. FtsService.pendingRebuilds).
            if (gap > 0) {
                log.info("[reconciliation] divergence FTS/ChromaDB pour '{}' (fts={}, chroma={}) — "
                        + "reconstruction depuis ChromaDB", defaultCollection, fts, chroma);
                ftsService.rebuildCollection(defaultCollection);
            }
        } catch (Exception e) {
            // ChromaDB indisponible (circuit ouvert, etc.) — non bloquant, réessai au prochain cycle.
            log.warn("[reconciliation] vérification ignorée : {}", e.getMessage());
        }
    }
}
