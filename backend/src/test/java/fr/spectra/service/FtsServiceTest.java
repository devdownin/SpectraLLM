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
            "coll-b7-race", "coll-b6-stale", "coll-b6-fresh", "coll-b6-down", "coll-header");

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
    void rebuildCollection_dropsEntriesMissingFromAuthoritativeSnapshot() {
        // Entrée périmée présente avant le début du rebuild.
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

        // Un chunk est déjà présent avant le début du rebuild.
        fts.indexChunks(List.of(chunk("live-1", "chunk indexé pendant le rebuild", "live.txt")), "coll-rebuild-merge");

        fts.rebuildCollection("coll-rebuild-merge");

        assertThat(fts.indexedCount("coll-rebuild-merge")).isEqualTo(1);
        assertThat(fts.search("rebuild", "coll-rebuild-merge", 5)).isEmpty();
        assertThat(fts.search("chromadb", "coll-rebuild-merge", 5)).isNotEmpty();
    }

    @Test
    void pruneStreamVersions_retainsOnlyPublishedGeneration() {
        ftsService.indexChunks(List.of(
                new TextChunk("old", "ancien", 0, "kafka://t/key", Map.of("sha256", "v1")),
                new TextChunk("new", "nouveau", 0, "kafka://t/key", Map.of("sha256", "v2")),
                chunk("other", "autre", "other.txt")), "versions");
        ftsService.pruneStreamVersions("kafka://t/key", "versions", "v2");
        assertThat(ftsService.indexedCount("versions")).isEqualTo(2);
        assertThat(ftsService.search("ancien", "versions", 5)).isEmpty();
        assertThat(ftsService.search("nouveau", "versions", 5)).hasSize(1);
        ftsService.pruneStreamVersions("kafka://t/key", "versions", null);
        assertThat(ftsService.indexedCount("versions")).isEqualTo(1);
    }

    @Test
    void search_hidesUnpublishedStreamGenerationBeforeTopN() {
        StreamVersionVisibility visibility = mock(StreamVersionVisibility.class);
        when(visibility.accepts("v2", "kafka://t/key")).thenReturn(true);
        when(visibility.snapshot()).thenReturn(visibility::accepts);
        ftsService.setStreamVersionVisibility(visibility);
        ftsService.indexChunks(List.of(
                new TextChunk("old", "commun", 0, "kafka://t/key", Map.of("sha256", "v1")),
                new TextChunk("new", "commun", 0, "kafka://t/key", Map.of("sha256", "v2"))), "versions");
        assertThat(ftsService.search("commun", "versions", 1))
                .extracting(BM25Index.ScoredDoc::id).containsExactly("new");
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

    @Test
    void rebuildCollection_deletionDuringPaginationRetainsLiveIndex() {
        ftsService.indexChunks(List.of(chunk("keep", "inchangé", "keep.txt"),
                chunk("remove", "supprimé", "remove.txt")), "coll-b7-race");
        when(chromaDb.getOrCreateCollection("coll-b7-race")).thenReturn("id-b7");
        when(chromaDb.getDocumentsPaged("id-b7", 500, 0)).thenAnswer(inv -> {
            ftsService.removeBySource("remove.txt", "coll-b7-race");
            List<String> ids = java.util.stream.IntStream.range(0, 500)
                    .mapToObj(i -> "snapshot-" + i).toList();
            return chromaPage(ids, ids);
        });
        ftsService.rebuildCollection("coll-b7-race");
        assertThat(ftsService.indexedCount("coll-b7-race")).isEqualTo(1);
        assertThat(ftsService.search("inchangé", "coll-b7-race", 5))
                .extracting(BM25Index.ScoredDoc::id).containsExactly("keep");
        assertThat(ftsService.search("supprimé", "coll-b7-race", 5)).isEmpty();
        verify(chromaDb, never()).getDocumentsPaged("id-b7", 500, 500);
    }

    @Test
    void rebuildCollection_replaysDeletionEvenWithoutLiveIndex() {
        when(chromaDb.getOrCreateCollection("coll-b7-race")).thenReturn("id-b7");
        when(chromaDb.getDocumentsPaged("id-b7", 500, 0)).thenAnswer(inv -> {
            ftsService.removeBySource("c1.txt", "coll-b7-race");
            return chromaPage(List.of("c1"), List.of("secret supprimé"));
        });
        ftsService.rebuildCollection("coll-b7-race");
        assertThat(ftsService.indexedCount("coll-b7-race")).isZero();
        assertThat(ftsService.search("secret", "coll-b7-race", 5)).isEmpty();
    }

    @Test
    void rebuildCollection_concurrentUpdateWinsOverSnapshot() {
        when(chromaDb.getOrCreateCollection("coll-b7-race")).thenReturn("id-b7");
        when(chromaDb.getDocumentsPaged("id-b7", 500, 0)).thenAnswer(inv -> {
            ftsService.indexChunks(List.of(chunk("c1", "nouvelle version", "c1.txt")), "coll-b7-race");
            return chromaPage(List.of("c1"), List.of("ancienne version"));
        });
        ftsService.rebuildCollection("coll-b7-race");
        assertThat(ftsService.indexedCount("coll-b7-race")).isEqualTo(1);
        assertThat(ftsService.search("nouvelle", "coll-b7-race", 5)).hasSize(1);
        assertThat(ftsService.search("ancienne", "coll-b7-race", 5)).isEmpty();
    }

    @Test
    void rebuildCollection_diskSnapshotReplaysConcurrentDeletion() throws Exception {
        BM25Index disk = new BM25Index();
        disk.add("c1", "secret supprimé", "c1.txt", "sha");
        writeDiskIndex("coll-b6-fresh", disk);
        when(chromaDb.getOrCreateCollection("coll-b6-fresh")).thenReturn("disk-id");
        when(chromaDb.count("disk-id")).thenAnswer(inv -> {
            ftsService.removeBySource("c1.txt", "coll-b6-fresh");
            return 1;
        });
        ftsService.rebuildCollection("coll-b6-fresh");
        assertThat(ftsService.indexedCount("coll-b6-fresh")).isZero();
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
        fresh.add("d1", "chunk persisté sur disque", "d.txt", null, ContextualHeader.terms(Map.of("sourceFile", "d.txt")));
        writeDiskIndex("coll-b6-fresh", fresh);

        when(chromaDb.getOrCreateCollection("coll-b6-fresh")).thenReturn("id-fresh");
        when(chromaDb.count("id-fresh")).thenReturn(1);
        when(chromaDb.getDocumentsPaged("id-fresh", 500, 0)).thenReturn(Map.of(
                "ids", List.of("d1"), "documents", List.of("chunk persisté sur disque"),
                "metadatas", List.of(Map.of("sourceFile", "d.txt"))));

        ftsService.rebuildCollection("coll-b6-fresh");

        assertThat(ftsService.indexedCount("coll-b6-fresh")).isEqualTo(1);
        assertThat(ftsService.search("persisté", "coll-b6-fresh", 5)).hasSize(1);
        verify(chromaDb).getDocumentsPaged("id-fresh", 500, 0);
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

    @Test
    void sameCountReplacementInvalidatesDiskAndLiveCorpus() throws Exception {
        BM25Index stale = new BM25Index();
        stale.add("old", "ancien fantome", "old.txt");
        writeDiskIndex("coll-b6-stale", stale);
        when(chromaDb.getOrCreateCollection("coll-b6-stale")).thenReturn("replacement-id");
        when(chromaDb.count("replacement-id")).thenReturn(1);
        when(chromaDb.getDocumentsPaged("replacement-id", 500, 0))
                .thenReturn(chromaPage(List.of("new"), List.of("nouveau contenu")));
        ftsService.rebuildCollection("coll-b6-stale");
        assertThat(ftsService.search("fantome", "coll-b6-stale", 5)).isEmpty();
        assertThat(ftsService.search("nouveau", "coll-b6-stale", 5)).hasSize(1);
        assertThat(ftsService.matchesChroma("coll-b6-stale")).isTrue();
        when(chromaDb.getDocumentsPaged("replacement-id", 500, 0))
                .thenReturn(chromaPage(List.of("new"), List.of("texte remplace meme identifiant")));
        assertThat(ftsService.matchesChroma("coll-b6-stale")).isFalse();
    }

    @Test
    void migrationBlocksLexicalServingAndMutations(@org.junit.jupiter.api.io.TempDir Path stateDir) {
        var guard = new CollectionMaintenanceGuard(stateDir);
        ftsService.setCollectionMaintenanceGuard(guard);
        guard.begin("coll-b7-race", "id");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ftsService.search("texte", "coll-b7-race", 5))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ftsService.indexChunks(
                List.of(chunk("id", "texte", "file.txt")), "coll-b7-race"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private TextChunk chunk(String id, String text, String sourceFile) {
        return new TextChunk(id, text, 0, sourceFile, Map.of());
    }

    // ── Champ d'en-tête (titre, fichier, section) ─────────────────────────────

    @Test
    void indexChunks_indexeLEnTeteSansLeMelerAuTexte() {
        TextChunk withTitle = new TextChunk("c1", "le délai est de trois mois", 0, "contrat.pdf",
                Map.of("sourceFile", "contrat.pdf", "title", "Contrat cadre", "section", "Résiliation"));
        ftsService.indexChunks(List.of(withTitle, chunk("c2", "le délai est de trois mois", "notice.txt")), "coll1");

        List<BM25Index.ScoredDoc> results = ftsService.search("délai résiliation contrat", "coll1", 5);

        assertThat(results).extracting(BM25Index.ScoredDoc::id).containsExactly("c1", "c2");
        assertThat(results.getFirst().text()).isEqualTo("le délai est de trois mois");
    }

    @Test
    void rebuildCollection_relitLEnTeteDepuisLesMetadonnees() {
        ChromaDbClient chroma = mock(ChromaDbClient.class);
        when(chroma.getOrCreateCollection("coll-header")).thenReturn("col-h");
        when(chroma.getDocumentsPaged("col-h", 500, 0)).thenReturn(Map.of(
                "ids", List.of("h1", "h2"),
                "documents", List.of("valeur par défaut 512", "valeur par défaut 512"),
                "metadatas", List.of(
                        Map.of("sourceFile", "guide.md", "section", "Embeddings > Fenêtre"),
                        Map.of("sourceFile", "faq.md"))));
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.chromadb()).thenReturn(
                new SpectraProperties.ChromaDbProperties("http://chroma:8000", "spectra_documents"));
        FtsService fts = new FtsService(chroma, props);

        fts.rebuildCollection("coll-header");

        assertThat(fts.search("fenêtre embeddings", "coll-header", 5))
                .extracting(BM25Index.ScoredDoc::id).containsExactly("h1");
    }
}
