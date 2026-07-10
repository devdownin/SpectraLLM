package fr.spectra.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.persistence.StreamSourceRepository;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.ChunkingService;
import fr.spectra.service.EmbeddingService;
import fr.spectra.service.FtsService;
import fr.spectra.service.GedService;
import fr.spectra.service.IngestionService;
import fr.spectra.service.IngestionTaskExecutor;
import fr.spectra.service.TextCleanerService;
import fr.spectra.service.UrlIngestionService;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import fr.spectra.service.extraction.JsonExtractor;
import fr.spectra.service.extraction.XmlExtractor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test de bout en bout du pipeline d'ingestion via la couche HTTP.
 *
 * <p>Chaîne RÉELLE exercée : {@code POST /api/ingest} → {@link IngestController} →
 * {@link IngestionService} → {@link IngestionTaskExecutor} avec extraction
 * ({@link JsonExtractor}/{@link XmlExtractor}), nettoyage ({@link TextCleanerService})
 * et découpage ({@link ChunkingService}) réels. Seules les frontières d'E/S externes
 * (embedding, ChromaDB, index BM25, dépôt GED) sont simulées.</p>
 *
 * <p>L'exécuteur est instancié directement (pas de proxy Spring), donc {@code @Async} est
 * inerte : l'ingestion s'exécute de façon SYNCHRONE pendant le POST, ce qui permet
 * d'observer l'état final ({@code COMPLETED}) juste après via {@code GET /api/ingest/{id}}.</p>
 */
class IngestionPipelineE2ETest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;
    private ChromaDbClient chromaDbClient;
    private FtsService ftsService;
    private EmbeddingService embeddingService;
    private IngestedFileRepository repository;

    @BeforeEach
    void setUp() {
        SpectraProperties.PipelineProperties pipeline =
                new SpectraProperties.PipelineProperties(512, 64, 10, 30, 120, 4);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);
        when(props.chromadb()).thenReturn(null);

        DocumentExtractorFactory factory = new DocumentExtractorFactory(
                List.of(new JsonExtractor(), new XmlExtractor()));
        TextCleanerService textCleaner = new TextCleanerService();
        ChunkingService chunkingService = new ChunkingService(props);

        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> List.of(0.1f, 0.2f, 0.3f)).toList();
        });

        chromaDbClient = mock(ChromaDbClient.class);
        when(chromaDbClient.getOrCreateCollection(anyString())).thenReturn("test-collection");

        ftsService = mock(FtsService.class);

        repository = mock(IngestedFileRepository.class);
        // Par défaut : aucun document déjà ingéré (rien n'est dédupliqué).
        when(repository.findAllById(anyIterable())).thenReturn(List.of());

        GedService gedService = mock(GedService.class);

        IngestionTaskExecutor executor = new IngestionTaskExecutor(
                factory, textCleaner, chunkingService, embeddingService, chromaDbClient, ftsService,
                new SimpleMeterRegistry(), props, 10, 50, 4);

        IngestionService ingestionService = new IngestionService(
                factory, textCleaner, chunkingService, embeddingService, chromaDbClient, ftsService,
                executor, repository, gedService,
                mock(StreamSourceRepository.class), props, 50, 4);

        IngestController controller = new IngestController(ingestionService, mock(UrlIngestionService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void uploadJson_runsFullPipeline_indexesChunksAndRecordsGed() throws Exception {
        String json = "[{\"topic\":\"peage\",\"payload\":\"Le passage au peage de sortie est "
                + "conforme et le montant retenu est nul pour ce vehicule.\"}]";
        MockMultipartFile file = new MockMultipartFile(
                "files", "passage.json", "application/json", json.getBytes());

        // 1. Upload → tâche créée ; l'exécuteur (non proxifié) traite de façon synchrone.
        String taskId = uploadAndGetTaskId(file);

        // 2. Le pipeline RÉEL a produit des chunks et les a indexés (embedding + ChromaDB + BM25).
        verify(embeddingService, atLeastOnce()).embedBatch(anyList());
        verify(chromaDbClient, atLeastOnce()).addDocuments(eq("test-collection"), anyList(), anyList());
        verify(ftsService, atLeastOnce()).indexChunks(anyList(), anyString());
        // 3. Le document ingéré est enregistré côté GED (dédup + traçabilité).
        verify(repository, atLeastOnce()).save(any(IngestedFileEntity.class));

        // 4. Statut final observable via l'API : COMPLETED avec au moins un chunk.
        mockMvc.perform(get("/api/ingest/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.chunksCreated", greaterThan(0)));
    }

    @Test
    void uploadDuplicate_isSkipped_notReindexed() throws Exception {
        // Simule un document déjà présent : la déduplication SHA-256 doit l'ignorer.
        when(repository.findAllById(anyIterable())).thenAnswer(inv -> {
            Iterable<String> ids = inv.getArgument(0);
            List<IngestedFileEntity> existing = new ArrayList<>();
            ids.forEach(id -> existing.add(new IngestedFileEntity(
                    id, "dup.json", "JSON", Instant.now(), 1, "test-collection", 0.5)));
            return existing;
        });

        MockMultipartFile file = new MockMultipartFile(
                "files", "dup.json", "application/json",
                "[{\"a\":\"contenu de test suffisant pour un chunk\"}]".getBytes());

        String taskId = uploadAndGetTaskId(file);

        // Rien n'est réindexé : aucun envoi vers ChromaDB pour un fichier dédupliqué.
        verify(chromaDbClient, never()).addDocuments(anyString(), anyList(), anyList());

        mockMvc.perform(get("/api/ingest/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.chunksCreated").value(0));
    }

    @SuppressWarnings("unchecked")
    private String uploadAndGetTaskId(MockMultipartFile file) throws Exception {
        String body = mockMvc.perform(multipart("/api/ingest").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").exists())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, Map.class).get("taskId").toString();
    }
}
