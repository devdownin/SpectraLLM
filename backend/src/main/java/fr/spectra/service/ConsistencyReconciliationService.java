package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
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
    private final SpectraProperties properties;
    private final MeterRegistry meterRegistry;
    private final String defaultCollection;

    // Valeurs exposées en gauges Prometheus (collection par défaut — compat tableaux de bord).
    private final AtomicLong chromaChunks    = new AtomicLong(-1);
    private final AtomicLong ftsChunks       = new AtomicLong(-1);
    private final AtomicLong dbChunks        = new AtomicLong(-1);
    private final AtomicLong ftsChromaGap    = new AtomicLong(0);

    // Gauges par collection (tag `collection`) — couvre aussi le flux Kafka et les
    // collections secondaires, qui n'étaient pas surveillés.
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> chromaByCollection =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> ftsByCollection =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> gapByCollection =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ConsistencyReconciliationService(IngestedFileRepository fileRepo,
                                            ChromaDbClient chromaDbClient,
                                            FtsService ftsService,
                                            SpectraProperties properties,
                                            MeterRegistry meterRegistry) {
        this.fileRepo = fileRepo;
        this.chromaDbClient = chromaDbClient;
        this.ftsService = ftsService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.defaultCollection = properties.chromadb() != null
                ? properties.chromadb().effectiveCollection() : "spectra_documents";

        meterRegistry.gauge("spectra.consistency.chroma.chunks", chromaChunks, AtomicLong::get);
        meterRegistry.gauge("spectra.consistency.fts.chunks", ftsChunks, AtomicLong::get);
        meterRegistry.gauge("spectra.consistency.db.chunks", dbChunks, AtomicLong::get);
        meterRegistry.gauge("spectra.consistency.fts_chroma_divergence", ftsChromaGap, AtomicLong::get);
    }

    /**
     * Instantané de cohérence au démarrage : dès que l'application est prête, on compare une
     * fois les comptes des trois sources de vérité et on journalise un état lisible — sans
     * attendre le premier cycle de réconciliation (T+2 min) et <b>sans déclencher de réparation</b>
     * (la reconstruction FTS reste du ressort du cycle périodique {@link #reconcile()}).
     *
     * <p>Objectif : donner à l'exploitant une visibilité immédiate au boot (et peupler les gauges
     * Prometheus tout de suite plutôt qu'après deux minutes). Le contrôle est purement informatif
     * et ne bloque jamais le démarrage : ChromaDB peut encore être indisponible à cet instant.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void snapshotOnStartup() {
        try {
            Long db = null;
            try {
                db = fileRepo.sumChunks();
                dbChunks.set(db != null ? db : 0L);
            } catch (Exception e) {
                log.warn("[startup-consistency] lecture DB impossible : {}", e.getMessage());
            }

            long chromaTotal = 0;
            boolean chromaReadable = false;
            int diverging = 0;
            Set<String> collections = targetCollections();
            for (String collection : collections) {
                try {
                    String collectionId = chromaDbClient.getOrCreateCollection(collection);
                    long chroma = chromaDbClient.count(collectionId);
                    long fts    = ftsService.indexedCount(collection);
                    long gap    = Math.abs(chroma - fts);
                    chromaReadable = true;
                    chromaTotal += chroma;

                    gaugeFor(chromaByCollection, "spectra.consistency.collection.chroma.chunks", collection).set(chroma);
                    gaugeFor(ftsByCollection, "spectra.consistency.collection.fts.chunks", collection).set(fts);
                    gaugeFor(gapByCollection, "spectra.consistency.collection.fts_chroma_divergence", collection).set(gap);
                    if (collection.equals(defaultCollection)) {
                        chromaChunks.set(chroma);
                        ftsChunks.set(fts);
                        ftsChromaGap.set(gap);
                    }

                    if (gap > 0) {
                        diverging++;
                        log.warn("[startup-consistency] '{}' divergent au démarrage : chroma={}, fts={} "
                                + "— réparation au prochain cycle de réconciliation", collection, chroma, fts);
                    }
                } catch (Exception e) {
                    log.warn("[startup-consistency] '{}' non vérifiable au démarrage "
                            + "(ChromaDB pas encore prêt ?) : {}", collection, e.getMessage());
                }
            }

            // Cas critique : la GED déclare des chunks mais ChromaDB est vide → volume vectoriel
            // perdu ou réinitialisé, le RAG ne répond plus sur le corpus. Correspond à l'alerte
            // Prometheus SpectraChromaEmptyButGedPopulated, mais visible dès le boot.
            if (chromaReadable && chromaTotal == 0 && db != null && db > 0) {
                log.error("[startup-consistency] ChromaDB vide alors que la GED déclare {} chunk(s) : "
                        + "volume vectoriel perdu ou réinitialisé — le RAG ne répondra pas sur le corpus. "
                        + "Ré-ingérez les documents ou restaurez le volume ChromaDB.", db);
            } else if (chromaReadable && diverging == 0) {
                log.info("[startup-consistency] cohérence OK au démarrage : {} collection(s) vérifiée(s), "
                        + "chroma={}, db={}", collections.size(), chromaTotal, db);
            }
        } catch (Exception e) {
            // Ne jamais empêcher le démarrage.
            log.warn("[startup-consistency] instantané impossible : {}", e.getMessage());
        }
    }

    /** Vérifie la cohérence toutes les heures (après un délai de démarrage). */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void reconcile() {
        Long db = null;
        try {
            db = fileRepo.sumChunks();
            dbChunks.set(db != null ? db : 0L);
        } catch (Exception e) {
            log.warn("[reconciliation] lecture DB impossible : {}", e.getMessage());
        }
        for (String collection : targetCollections()) {
            reconcileCollection(collection, db);
        }
    }

    /**
     * Collections à surveiller : la collection par défaut, celles référencées par la GED
     * (ré-indexations, collections secondaires) et celle du flux Kafka si activé —
     * seule la collection par défaut l'était auparavant.
     */
    java.util.Set<String> targetCollections() {
        java.util.Set<String> collections = new java.util.LinkedHashSet<>();
        collections.add(defaultCollection);
        try {
            collections.addAll(fileRepo.findDistinctCollectionNames());
        } catch (Exception e) {
            log.warn("[reconciliation] collections GED illisibles : {}", e.getMessage());
        }
        SpectraProperties.KafkaProperties kafka = properties.kafka();
        if (kafka != null && Boolean.TRUE.equals(kafka.enabled())) {
            collections.add(kafka.effectiveCollection());
        }
        return collections;
    }

    private void reconcileCollection(String collection, Long dbTotal) {
        try {
            String collectionId = chromaDbClient.getOrCreateCollection(collection);
            long chroma = chromaDbClient.count(collectionId);
            long fts    = ftsService.indexedCount(collection);
            long gap    = Math.abs(chroma - fts);

            gaugeFor(chromaByCollection, "spectra.consistency.collection.chroma.chunks", collection).set(chroma);
            gaugeFor(ftsByCollection, "spectra.consistency.collection.fts.chunks", collection).set(fts);
            gaugeFor(gapByCollection, "spectra.consistency.collection.fts_chroma_divergence", collection).set(gap);
            if (collection.equals(defaultCollection)) {
                chromaChunks.set(chroma);
                ftsChunks.set(fts);
                ftsChromaGap.set(gap);
            }

            // Réparation : toute divergence FTS/ChromaDB déclenche une reconstruction de
            // l'index BM25 depuis ChromaDB (source de vérité) — y compris un index FTS
            // périmé non vide (chunks fantômes après un reset de ChromaDB, ajouts perdus
            // par un arrêt brutal dans la fenêtre de flush différé…). La reconstruction
            // est sûre pendant une ingestion : les mutations concurrentes sont répliquées
            // dans l'index cible (cf. FtsService.pendingRebuilds).
            if (gap > 0) {
                log.warn("[reconciliation] divergence FTS/ChromaDB sur '{}' (chroma={}, fts={}, db={}) — "
                        + "reconstruction depuis ChromaDB", collection, chroma, fts, dbTotal);
                ftsService.rebuildCollection(collection);
            } else {
                log.debug("[reconciliation] '{}' cohérent : chroma={}, fts={}", collection, chroma, fts);
            }
        } catch (Exception e) {
            // ChromaDB indisponible (circuit ouvert, etc.) — non bloquant, réessai au prochain cycle.
            log.warn("[reconciliation] vérification de '{}' ignorée : {}", collection, e.getMessage());
        }
    }

    private AtomicLong gaugeFor(java.util.concurrent.ConcurrentHashMap<String, AtomicLong> holder,
                                String metric, String collection) {
        return holder.computeIfAbsent(collection, c ->
                meterRegistry.gauge(metric, io.micrometer.core.instrument.Tags.of("collection", c),
                        new AtomicLong(-1), AtomicLong::get));
    }
}
