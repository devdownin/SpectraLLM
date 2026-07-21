package fr.spectra.integration;

import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.AuditLogRepository;
import fr.spectra.persistence.DocumentModelLinkRepository;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.persistence.StreamSourceRepository;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.ChunkingService;
import fr.spectra.service.ConsistencyReconciliationService;
import fr.spectra.service.EmbeddingClient;
import fr.spectra.service.EmbeddingService;
import fr.spectra.service.FtsService;
import fr.spectra.service.GedService;
import fr.spectra.service.IngestionService;
import fr.spectra.service.IngestionTaskExecutor;
import fr.spectra.service.TextCleanerService;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import fr.spectra.service.extraction.TxtExtractor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests d'intégration de cohérence ingestion/GED contre un <b>ChromaDB réel</b>.
 *
 * <p>Raison d'être : les bugs les plus graves de l'audit ingestion/GED (chunks dupliqués à la
 * ré-ingestion forcée, purge de rétention laissant les index pleins, collisions d'homonymes)
 * étaient invisibles aux tests unitaires — tous mockent ChromaDB. Ici, seuls l'embedding
 * (vecteurs déterministes) et la persistance H2 (fake en mémoire) sont simulés ; le pipeline
 * extraction → chunking → indexation → suppression parle à un vrai serveur Chroma.</p>
 *
 * <p>Exécution :</p>
 * <ul>
 *   <li><b>CI / poste avec Docker</b> — un conteneur {@code chromadb/chroma} jetable est
 *       démarré automatiquement (Testcontainers), même image que la stack compose.</li>
 *   <li><b>Sans Docker</b> — le test est ignoré ({@code Assumptions}), sauf si
 *       {@code SPECTRA_TEST_CHROMA_URL} pointe vers un serveur Chroma déjà lancé
 *       (ex. {@code chroma run --port 8123}).</li>
 * </ul>
 */
class ChromaDbConsistencyIntegrationTest {

    private static final String EXTERNAL_URL = System.getenv("SPECTRA_TEST_CHROMA_URL");

    private static GenericContainer<?> chromaContainer;
    private static String chromaBaseUrl;

    @TempDir
    Path tempArchiveDir;

    // Fabriqués par collection de test (isolation entre méthodes).
    private String collectionName;
    private ChromaDbClient chromaDbClient;
    private FtsService ftsService;
    private IngestionService ingestionService;
    private GedService gedService;
    private Map<String, IngestedFileEntity> gedDb;

    @BeforeAll
    static void startChroma() {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            chromaBaseUrl = EXTERNAL_URL;
            return;
        }
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker indisponible et SPECTRA_TEST_CHROMA_URL non défini — test d'intégration ignoré");
        chromaContainer = new GenericContainer<>("chromadb/chroma:latest")
                .withExposedPorts(8000)
                .withEnv("ANONYMIZED_TELEMETRY", "FALSE")
                .waitingFor(Wait.forHttp("/api/v2/heartbeat").forPort(8000)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        chromaContainer.start();
        chromaBaseUrl = "http://" + chromaContainer.getHost() + ":" + chromaContainer.getMappedPort(8000);
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @AfterAll
    static void stopChroma() {
        if (chromaContainer != null) {
            chromaContainer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        // Collection unique par test : pas d'interférence entre méthodes ni entre exécutions.
        collectionName = "it-consistency-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        SpectraProperties.PipelineProperties pipeline =
                new SpectraProperties.PipelineProperties(64, 8, 10, 30, 120, 2);
        SpectraProperties.ChromaDbProperties chromaProps =
                new SpectraProperties.ChromaDbProperties(chromaBaseUrl, collectionName);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);
        when(props.chromadb()).thenReturn(chromaProps);

        WebClient chromaWebClient = WebClient.builder().baseUrl(chromaBaseUrl).build();
        chromaDbClient = new ChromaDbClient(chromaWebClient, props, null);
        ftsService = new FtsService(chromaDbClient, props);

        // Embedding déterministe : vecteur 8D dérivé du hash du texte (dimension stable,
        // textes différents → vecteurs différents), sans dépendre d'un serveur d'embedding.
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(ChromaDbConsistencyIntegrationTest::vectorFor).toList();
        });
        EmbeddingService embeddingService = new EmbeddingService(embeddingClient);

        // Persistance GED simulée par une map : le sujet du test est la cohérence Chroma.
        gedDb = new ConcurrentHashMap<>();
        IngestedFileRepository fileRepo = inMemoryFileRepo(gedDb);
        DocumentModelLinkRepository linkRepo = mock(DocumentModelLinkRepository.class);
        AuditLogRepository auditRepo = mock(AuditLogRepository.class);

        gedService = new GedService(fileRepo, linkRepo, auditRepo,
                chromaDbClient, ftsService, tempArchiveDir.toString());

        DocumentExtractorFactory factory = new DocumentExtractorFactory(List.of(new TxtExtractor()));
        TextCleanerService textCleaner = new TextCleanerService();
        ChunkingService chunkingService = new ChunkingService(props);
        IngestionTaskExecutor executor = new IngestionTaskExecutor(
                factory, textCleaner, chunkingService, embeddingService, chromaDbClient, ftsService,
                new SimpleMeterRegistry(), props, 10, 50, 2);

        ingestionService = new IngestionService(
                factory, textCleaner, chunkingService, embeddingService, chromaDbClient, ftsService,
                executor, fileRepo, gedService, mock(StreamSourceRepository.class), props, 50, 2, 0);
    }

    // ── Scénarios de l'audit ─────────────────────────────────────────────────

    @Test
    void forceReingest_replacesChunksInsteadOfDuplicating() throws Exception {
        byte[] content = corpus("contrat cadre de maintenance applicative", 40);

        ingestionService.submit(List.of(file("contrat.txt", content)), false);
        String collectionId = chromaDbClient.getOrCreateCollection(collectionName);
        int afterFirst = chromaDbClient.count(collectionId);
        assertThat(afterFirst).isGreaterThan(0);

        // Régression B1 : chaque force=true dupliquait tous les chunks du document.
        ingestionService.submit(List.of(file("contrat.txt", content)), true);
        assertThat(chromaDbClient.count(collectionId)).isEqualTo(afterFirst);

        // Versioning GED de la ré-ingestion (R4).
        assertThat(gedDb.get(sha256(content)).getVersion()).isEqualTo(2);
    }

    @Test
    void deleteDocument_purgesVectorAndBm25Indexes() throws Exception {
        byte[] content = corpus("procédure d'évacuation incendie du bâtiment", 40);
        String hash = sha256(content);

        ingestionService.submit(List.of(file("procedure.txt", content)), false);
        String collectionId = chromaDbClient.getOrCreateCollection(collectionName);
        assertThat(chromaDbClient.count(collectionId)).isGreaterThan(0);
        assertThat(ftsService.indexedCount(collectionName)).isGreaterThan(0);

        gedService.deleteDocument(hash, "integration-test");

        // Régression B2/B4 : les chunks restaient servis par le RAG après suppression/purge.
        assertThat(chromaDbClient.count(collectionId)).isZero();
        assertThat(ftsService.indexedCount(collectionName)).isZero();
        assertThat(gedDb).doesNotContainKey(hash);
    }

    @Test
    void homonymousDocuments_deletionTargetsContentIdentity() throws Exception {
        // Deux documents DIFFÉRENTS portant le MÊME nom de fichier.
        byte[] contentA = corpus("rapport annuel division nord exercice comptable", 40);
        byte[] contentB = corpus("compte rendu réunion sécurité chantier sud", 40);

        ingestionService.submit(List.of(file("rapport.txt", contentA)), false);
        String collectionId = chromaDbClient.getOrCreateCollection(collectionName);
        int chunksA = chromaDbClient.count(collectionId);
        ingestionService.submit(List.of(file("rapport.txt", contentB)), false);
        int total = chromaDbClient.count(collectionId);
        assertThat(total).isGreaterThan(chunksA);

        // Régression B3 : la suppression par nom de source effaçait aussi les chunks de
        // l'homonyme. L'identité sha256 des chunks doit ne viser que le document supprimé.
        gedService.deleteDocument(sha256(contentA), "integration-test");

        assertThat(chromaDbClient.count(collectionId)).isEqualTo(total - chunksA);
        assertThat(gedDb).containsKey(sha256(contentB));
    }

    @Test
    void reconcile_multiCollection_rebuildsOnlyTheDivergentCollection() throws Exception {
        // Collection A = collectionName (câblée par setUp), ingérée et cohérente.
        byte[] contentA = corpus("politique de sauvegarde et de restauration des données", 40);
        ingestionService.submit(List.of(file("docA.txt", contentA)), false);
        String idA = chromaDbClient.getOrCreateCollection(collectionName);
        int chromaA = chromaDbClient.count(idA);
        assertThat(chromaA).isGreaterThan(0);
        assertThat(ftsService.indexedCount(collectionName)).isEqualTo(chromaA);

        // Collection B, ingérée dans les MÊMES stores partagés (même ChromaDB, même FtsService).
        String collectionB = "it-consistency-b-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        IngestionService serviceB = serviceForSharedStores(collectionB, new ConcurrentHashMap<>());
        byte[] contentB = corpus("procédure de gestion des incidents de sécurité", 40);
        serviceB.submit(List.of(file("docB.txt", contentB)), false);
        String idB = chromaDbClient.getOrCreateCollection(collectionB);
        int chromaB = chromaDbClient.count(idB);
        assertThat(chromaB).isGreaterThan(0);
        assertThat(ftsService.indexedCount(collectionB)).isEqualTo(chromaB);

        // Divergence UNIQUEMENT sur B : on vide son index BM25 en gardant ChromaDB intact
        // (perte d'un flush différé, reset partiel…). A doit rester cohérente.
        ftsService.removeBySource("docB.txt", collectionB);
        assertThat(ftsService.indexedCount(collectionB)).isZero();
        assertThat(ftsService.indexedCount(collectionName)).isEqualTo(chromaA);

        // Réconciliation multi-collections : A et B sont surveillées ; seule B diverge et doit
        // être reconstruite depuis le ChromaDB réel — A ne doit pas être touchée.
        IngestedFileRepository reconRepo = mock(IngestedFileRepository.class);
        when(reconRepo.sumChunks()).thenReturn((long) (chromaA + chromaB));
        when(reconRepo.findDistinctCollectionNames()).thenReturn(List.of(collectionName, collectionB));
        SpectraProperties.ChromaDbProperties reconChroma =
                new SpectraProperties.ChromaDbProperties(chromaBaseUrl, collectionName);
        SpectraProperties reconProps = mock(SpectraProperties.class);
        when(reconProps.chromadb()).thenReturn(reconChroma);
        when(reconProps.kafka()).thenReturn(null);
        ConsistencyReconciliationService reconciliation = new ConsistencyReconciliationService(
                reconRepo, chromaDbClient, ftsService, reconProps, new SimpleMeterRegistry());

        reconciliation.reconcile();

        assertThat(ftsService.indexedCount(collectionB)).isEqualTo(chromaB); // reconstruite
        assertThat(ftsService.indexedCount(collectionName)).isEqualTo(chromaA); // intacte
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Construit une seconde {@link IngestionService} câblée sur une AUTRE collection mais
     * partageant le ChromaDB et le FtsService du test — pour peupler plusieurs collections
     * dans les mêmes stores et exercer la réconciliation multi-collections.
     */
    private IngestionService serviceForSharedStores(String collection,
                                                    Map<String, IngestedFileEntity> ged) {
        SpectraProperties.PipelineProperties pipeline =
                new SpectraProperties.PipelineProperties(64, 8, 10, 30, 120, 2);
        SpectraProperties.ChromaDbProperties chromaProps =
                new SpectraProperties.ChromaDbProperties(chromaBaseUrl, collection);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);
        when(props.chromadb()).thenReturn(chromaProps);

        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(ChromaDbConsistencyIntegrationTest::vectorFor).toList();
        });
        EmbeddingService embeddingService = new EmbeddingService(embeddingClient);

        IngestedFileRepository fileRepo = inMemoryFileRepo(ged);
        GedService gedForB = new GedService(fileRepo, mock(DocumentModelLinkRepository.class),
                mock(AuditLogRepository.class), chromaDbClient, ftsService, tempArchiveDir.toString());
        DocumentExtractorFactory factory = new DocumentExtractorFactory(List.of(new TxtExtractor()));
        TextCleanerService textCleaner = new TextCleanerService();
        ChunkingService chunkingService = new ChunkingService(props);
        IngestionTaskExecutor executor = new IngestionTaskExecutor(
                factory, textCleaner, chunkingService, embeddingService, chromaDbClient, ftsService,
                new SimpleMeterRegistry(), props, 10, 50, 2);
        return new IngestionService(
                factory, textCleaner, chunkingService, embeddingService, chromaDbClient, ftsService,
                executor, fileRepo, gedForB, mock(StreamSourceRepository.class), props, 50, 2, 0);
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, "text/plain", content);
    }

    /** Corpus de plusieurs phrases répétées avec variation → plusieurs chunks (max 64 tokens). */
    private static byte[] corpus(String theme, int sentences) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences; i++) {
            sb.append("Paragraphe ").append(i).append(" du document : ").append(theme)
              .append(", section numéro ").append(i).append(".\n\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** Vecteur 8D déterministe dérivé du hash du texte. */
    private static List<Float> vectorFor(String text) {
        int h = text.hashCode();
        List<Float> v = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            v.add(((h >> (i * 4)) & 0xF) / 15.0f + 0.01f);
        }
        return v;
    }

    /** Fake en mémoire des seules méthodes de IngestedFileRepository utilisées par le flux. */
    @SuppressWarnings("unchecked")
    private static IngestedFileRepository inMemoryFileRepo(Map<String, IngestedFileEntity> db) {
        IngestedFileRepository repo = mock(IngestedFileRepository.class);
        when(repo.existsById(anyString())).thenAnswer(inv -> db.containsKey(inv.getArgument(0, String.class)));
        when(repo.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(db.get(inv.getArgument(0, String.class))));
        when(repo.findAllById(anyIterable())).thenAnswer(inv -> {
            List<IngestedFileEntity> out = new ArrayList<>();
            for (String id : (Iterable<String>) inv.getArgument(0)) {
                IngestedFileEntity e = db.get(id);
                if (e != null) out.add(e);
            }
            return out;
        });
        when(repo.findByFileName(anyString())).thenAnswer(inv -> db.values().stream()
                .filter(e -> inv.getArgument(0, String.class).equals(e.getFileName())).toList());
        when(repo.save(any(IngestedFileEntity.class))).thenAnswer(inv -> {
            IngestedFileEntity e = inv.getArgument(0);
            db.put(e.getSha256(), e);
            return e;
        });
        doAnswer(inv -> db.remove(inv.getArgument(0, IngestedFileEntity.class).getSha256()))
                .when(repo).delete(any(IngestedFileEntity.class));
        return repo;
    }
}
