package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.model.TextChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de FtsService — gestion des indices BM25 par collection.
 * ChromaDbClient est mocké ; les indices sont manipulés via indexChunks/removeBySource.
 */
class FtsServiceTest {

    /** Doit refléter FtsService.INDEX_DIR (répertoire de persistance des index). */
    private static final Path INDEX_DIR = Path.of("./data/fts-index");
    private static final List<String> TEST_COLLECTIONS = List.of(
            "coll-b7-race", "coll-b6-stale", "coll-b6-fresh", "coll-b6-down");

    private ChromaDbClient chromaDb;
    private FtsService ftsService;

    @BeforeEach
    void setUp() throws Exception {
        chromaDb = mock(ChromaDbClient.class);
        // Évite que rebuildDefaultIndexAsync explose au démarrage
        when(chromaDb.getOrCreateCollection("spectra_documents"))
                .thenThrow(new RuntimeException("ChromaDB absent en test"));

        SpectraProperties.ChromaDbProperties chromaProps =
                new SpectraProperties.ChromaDbProperties("http://chroma:8000", "spectra_documents");
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.chromadb()).thenReturn(chromaProps);

        ftsService = new FtsService(chromaDb, props);
        cleanDiskIndices();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanDiskIndices();
    }

    private void cleanDiskIndices() throws Exception {
        for (String coll : TEST_COLLECTIONS) {
            Files.deleteIfExists(INDEX_DIR.resolve(coll + ".bin"));
        }
    }

    private void writeDiskIndex(String collectionName, BM25Index index) throws Exception {
        Files.createDirectories(INDEX_DIR);
        try (ObjectOutputStream oos = new ObjectOutputStream(
                Files.newOutputStream(INDEX_DIR.resolve(collectionName + ".bin")))) {
            oos.writeObject(index);
        }
    }

    private static Map<String, Object> chromaPage(List<String> ids, List<String> docs) {
        return Map.of("ids", ids, "documents", docs,
                "metadatas", ids.stream().map(i -> Map.of("sourceFile", i + ".txt")).toList());
    }

    // ── indexChunks ───────────────────────────────────────────────────────────

    @Test
    void indexChunks_singleChunk_indexedCountIsOne() {
        ftsService.indexChunks(List.of(chunk("c1", "contenu du document", "doc.txt")), "coll1");
        assertThat(ftsService.indexedCount("coll1")).isEqualTo(1);
    }

    @Test
    void indexChunks_multipleBatches_countCumulates() {
        ftsService.indexChunks(List.of(
                chunk("c1", "premier chunk", "a.txt"),
                chunk("c2", "deuxième chunk", "a.txt")), "coll1");
        ftsService.indexChunks(List.of(
                chunk("c3", "troisième chunk", "b.txt")), "coll1");

        assertThat(ftsService.indexedCount("coll1")).isEqualTo(3);
    }

    @Test
    void indexChunks_differentCollections_separateIndices() {
        ftsService.indexChunks(List.of(chunk("c1", "texte A", "a.txt")), "collA");
        ftsService.indexChunks(List.of(chunk("c2", "texte B", "b.txt")), "collB");

        assertThat(ftsService.indexedCount("collA")).isEqualTo(1);
        assertThat(ftsService.indexedCount("collB")).isEqualTo(1);
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_emptyIndex_returnsEmptyList() {
        assertThat(ftsService.search("query", "coll1", 5)).isEmpty();
    }

    @Test
    void search_afterIndexing_findsChunk() {
        ftsService.indexChunks(List.of(
                chunk("c1", "spectra plateforme rag intelligence artificielle", "doc.txt")), "coll1");

        List<BM25Index.ScoredDoc> results = ftsService.search("spectra", "coll1", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("c1");
    }

    @Test
    void search_topNHonoured() {
        for (int i = 0; i < 10; i++) {
            ftsService.indexChunks(List.of(chunk("c" + i, "document rag " + i, "f.txt")), "coll1");
        }
        assertThat(ftsService.search("document", "coll1", 3)).hasSize(3);
    }

    @Test
    void search_unknownCollection_returnsEmptyList() {
        assertThat(ftsService.search("query", "inexistante", 5)).isEmpty();
    }

    // ── removeBySource ────────────────────────────────────────────────────────

    @Test
    void removeBySource_removesChunksOfSource() {
        ftsService.indexChunks(List.of(
                chunk("c1", "doc source A premier", "sourceA.txt"),
                chunk("c2", "doc source A second", "sourceA.txt"),
                chunk("c3", "doc source B", "sourceB.txt")), "coll1");

        ftsService.removeBySource("sourceA.txt", "coll1");

        assertThat(ftsService.indexedCount("coll1")).isEqualTo(1);
        assertThat(ftsService.search("sourceA", "coll1", 5)).isEmpty();
    }

    @Test
    void removeBySource_unknownCollection_noException() {
        // Ne doit pas lever d'exception
        ftsService.removeBySource("fichier.txt", "inexistante");
    }

    // ── rebuildCollection : fusion avec les chunks indexés en direct ──────────

    @Test
    void rebuildCollection_mergesWithLiveIndexInsteadOfReplacing() {
        // Chunks indexés en direct PENDANT le rebuild (ingestion concurrente au démarrage).
        ChromaDbClient chromaDb = mock(ChromaDbClient.class);
        when(chromaDb.getOrCreateCollection("coll-rebuild-merge")).thenReturn("col-id");
        // ChromaDB contient un seul chunk « historique ».
        when(chromaDb.getDocumentsPaged("col-id", 500, 0)).thenReturn(Map.of(
                "ids", List.of("old-1"),
                "documents", List.of("ancien chunk reconstruit depuis chromadb"),
                "metadatas", List.of(Map.of("sourceFile", "ancien.txt"))));

        SpectraProperties.ChromaDbProperties chromaProps =
                new SpectraProperties.ChromaDbProperties("http://chroma:8000", "spectra_documents");
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.chromadb()).thenReturn(chromaProps);
        FtsService fts = new FtsService(chromaDb, props);

        // Un chunk arrive en direct avant la fin du rebuild.
        fts.indexChunks(List.of(chunk("live-1", "chunk indexé pendant le rebuild", "live.txt")), "coll-rebuild-merge");

        fts.rebuildCollection("coll-rebuild-merge");

        // Régression : l'ancien indices.put écrasait l'index vivant — le chunk live
        // disparaissait de BM25 jusqu'au rebuild suivant.
        assertThat(fts.indexedCount("coll-rebuild-merge")).isEqualTo(2);
        assertThat(fts.search("rebuild", "coll-rebuild-merge", 5)).isNotEmpty();
        assertThat(fts.search("chromadb", "coll-rebuild-merge", 5)).isNotEmpty();
    }

    // ── getStatus ─────────────────────────────────────────────────────────────

    @Test
    void getStatus_emptyCollection_notReady() {
        FtsService.FtsStatus status = ftsService.getStatus("vide");
        assertThat(status.ready()).isFalse();
        assertThat(status.indexedChunks()).isZero();
    }

    @Test
    void getStatus_afterIndexing_ready() {
        ftsService.indexChunks(List.of(chunk("c1", "texte indexé", "doc.txt")), "coll1");
        FtsService.FtsStatus status = ftsService.getStatus("coll1");

        assertThat(status.ready()).isTrue();
        assertThat(status.indexedChunks()).isEqualTo(1);
        assertThat(status.collections()).isEqualTo("coll1");
    }

    @Test
    void getAggregatedStatus_multipleCollections_sumsAll() {
        ftsService.indexChunks(List.of(chunk("c1", "texte A", "a.txt")), "collA");
        ftsService.indexChunks(List.of(
                chunk("c2", "texte B", "b.txt"),
                chunk("c3", "texte C", "c.txt")), "collB");

        FtsService.FtsStatus agg = ftsService.getAggregatedStatus();
        assertThat(agg.indexedChunks()).isEqualTo(3);
        assertThat(agg.ready()).isTrue();
    }

    @Test
    void getAggregatedStatus_noCollections_notReady() {
        FtsService.FtsStatus agg = ftsService.getAggregatedStatus();
        assertThat(agg.ready()).isFalse();
        assertThat(agg.indexedChunks()).isZero();
    }

    // ── rebuildCollection : course rebuild/ingestion (B7) ────────────────────

    @Test
    void rebuildCollection_concurrentIngestionDuringRebuild_chunksNotLost() {
        when(chromaDb.getOrCreateCollection("coll-b7-race")).thenReturn("id-b7");
        // Pendant que le rebuild pagine ChromaDB, une ingestion concurrente indexe un chunk :
        // sans réplication vers l'index cible, il était écrasé par la publication finale.
        when(chromaDb.getDocumentsPaged(eq("id-b7"), anyInt(), eq(0)))
                .thenAnswer(inv -> {
                    ftsService.indexChunks(List.of(
                            chunk("live", "chunk ingéré pendant le rebuild", "live.txt")), "coll-b7-race");
                    return chromaPage(List.of("c1"), List.of("chunk provenant de chroma"));
                });

        ftsService.rebuildCollection("coll-b7-race");

        assertThat(ftsService.indexedCount("coll-b7-race")).isEqualTo(2);
        assertThat(ftsService.search("ingéré", "coll-b7-race", 5))
                .extracting(BM25Index.ScoredDoc::id).containsExactly("live");
        assertThat(ftsService.search("chroma", "coll-b7-race", 5))
                .extracting(BM25Index.ScoredDoc::id).containsExactly("c1");
    }

    // ── rebuildCollection : fraîcheur de l'index disque (B6) ─────────────────

    @Test
    void rebuildCollection_staleDiskIndex_rebuiltFromChroma() throws Exception {
        // Index disque périmé : 1 chunk, alors que ChromaDB en contient 2.
        BM25Index stale = new BM25Index();
        stale.add("old", "ancien chunk fantôme", "old.txt");
        writeDiskIndex("coll-b6-stale", stale);

        when(chromaDb.getOrCreateCollection("coll-b6-stale")).thenReturn("id-stale");
        when(chromaDb.count("id-stale")).thenReturn(2);
        when(chromaDb.getDocumentsPaged(eq("id-stale"), anyInt(), eq(0)))
                .thenReturn(chromaPage(List.of("c1", "c2"), List.of("premier chunk", "deuxième chunk")));

        ftsService.rebuildCollection("coll-b6-stale");

        assertThat(ftsService.indexedCount("coll-b6-stale")).isEqualTo(2);
        assertThat(ftsService.search("fantôme", "coll-b6-stale", 5)).isEmpty();
    }

    @Test
    void rebuildCollection_freshDiskIndex_usedWithoutChromaRebuild() throws Exception {
        BM25Index fresh = new BM25Index();
        fresh.add("d1", "chunk persisté sur disque", "d.txt");
        writeDiskIndex("coll-b6-fresh", fresh);

        when(chromaDb.getOrCreateCollection("coll-b6-fresh")).thenReturn("id-fresh");
        when(chromaDb.count("id-fresh")).thenReturn(1);

        ftsService.rebuildCollection("coll-b6-fresh");

        assertThat(ftsService.indexedCount("coll-b6-fresh")).isEqualTo(1);
        assertThat(ftsService.search("persisté", "coll-b6-fresh", 5)).hasSize(1);
        verify(chromaDb, never()).getDocumentsPaged(eq("id-fresh"), anyInt(), anyInt());
    }

    @Test
    void rebuildCollection_chromaUnreachable_diskIndexUsedDegraded() throws Exception {
        BM25Index disk = new BM25Index();
        disk.add("d1", "chunk du disque", "d.txt");
        writeDiskIndex("coll-b6-down", disk);

        when(chromaDb.getOrCreateCollection("coll-b6-down"))
                .thenThrow(new RuntimeException("ChromaDB injoignable"));

        ftsService.rebuildCollection("coll-b6-down");

        // Fraîcheur invérifiable → mode dégradé : l'index disque est utilisé tel quel.
        assertThat(ftsService.indexedCount("coll-b6-down")).isEqualTo(1);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private TextChunk chunk(String id, String text, String sourceFile) {
        return new TextChunk(id, text, 0, sourceFile, Map.of());
    }
}
