package fr.spectra.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ré-indexation en place : les vecteurs sont recalculés page par page avec le modèle
 * actif, et l'estampille n'est mise à jour qu'EN DERNIER (la collection reste bloquée
 * pour le RAG tant que tous les vecteurs ne sont pas cohérents).
 */
class EmbeddingReindexServiceTest {

    private ChromaDbClient chromaDbClient;
    private EmbeddingService embeddingService;
    private EmbeddingReindexService service;

    @BeforeEach
    void setUp() {
        chromaDbClient = mock(ChromaDbClient.class);
        embeddingService = mock(EmbeddingService.class);
        ModelRegistryService modelRegistry = mock(ModelRegistryService.class);
        when(modelRegistry.getActiveEmbeddingModel()).thenReturn("nouveau-modele");
        service = new EmbeddingReindexService(chromaDbClient, embeddingService, modelRegistry);
    }

    /** Attend (max 5 s) qu'un statut satisfasse le prédicat — le job tourne en async. */
    private void awaitStatus(Predicate<EmbeddingReindexService.ReindexStatus> predicate) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (service.statuses().stream().anyMatch(predicate)) return;
            Thread.sleep(25);
        }
        Assertions.fail("Condition non atteinte en 5s — statuts: " + service.statuses());
    }

    @Test
    void reindex_recalculeLesVecteursPuisReestampilleEnDernier() throws Exception {
        when(chromaDbClient.resolveCollectionIdUnchecked("docs")).thenReturn("col-1");
        when(chromaDbClient.count("col-1")).thenReturn(2);
        when(chromaDbClient.getDocumentsPaged(eq("col-1"), anyInt(), eq(0))).thenReturn(Map.of(
                "ids", List.of("a", "b"),
                "documents", List.of("texte A", "texte B")));
        when(chromaDbClient.getDocumentsPaged(eq("col-1"), anyInt(), eq(16))).thenReturn(Map.of(
                "ids", List.of(), "documents", List.of()));
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of(
                List.of(0.1f), List.of(0.2f)));

        service.start("docs");
        awaitStatus(s -> "docs".equals(s.collection()) && "COMPLETED".equals(s.status()));

        // L'estampille est posée APRÈS la mise à jour des vecteurs.
        var order = inOrder(chromaDbClient);
        order.verify(chromaDbClient).updateEmbeddings(eq("col-1"), eq(List.of("a", "b")), anyList());
        order.verify(chromaDbClient).updateEmbeddingStamp("docs", "col-1", "nouveau-modele");

        var status = service.statuses().stream()
                .filter(s -> "docs".equals(s.collection())).findFirst().orElseThrow();
        assertThat(status.processed()).isEqualTo(2);
        assertThat(status.total()).isEqualTo(2);
        assertThat(status.targetModel()).isEqualTo("nouveau-modele");
    }

    @Test
    void reindex_echecEmbedding_statutFailed_sansReestampillage() throws Exception {
        when(chromaDbClient.resolveCollectionIdUnchecked("docs")).thenReturn("col-1");
        when(chromaDbClient.count("col-1")).thenReturn(1);
        when(chromaDbClient.getDocumentsPaged(eq("col-1"), anyInt(), eq(0))).thenReturn(Map.of(
                "ids", List.of("a"), "documents", List.of("texte")));
        when(embeddingService.embedBatch(anyList()))
                .thenThrow(new RuntimeException("embedding indisponible"));

        service.start("docs");
        awaitStatus(s -> "FAILED".equals(s.status())
                && s.error() != null && s.error().contains("embedding indisponible"));

        // Estampille INTACTE : la collection reste marquée avec l'ancien modèle (et bloquée).
        verify(chromaDbClient, never()).updateEmbeddingStamp(anyString(), anyString(), anyString());
    }

    @Test
    void start_reindexationDejaEnCours_rejeteEn409() {
        // Premier job bloqué sur la lecture de page → le verrou reste pris.
        when(chromaDbClient.resolveCollectionIdUnchecked("lente")).thenReturn("col-slow");
        when(chromaDbClient.count("col-slow")).thenReturn(1);
        when(chromaDbClient.getDocumentsPaged(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(2_000);
            return Map.of("ids", List.of(), "documents", List.of());
        });

        service.start("lente");

        assertThatThrownBy(() -> service.start("autre"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("déjà en cours");
    }

    @Test
    void reindex_chunksVides_conserventLeurVecteur() throws Exception {
        when(chromaDbClient.resolveCollectionIdUnchecked("docs")).thenReturn("col-1");
        when(chromaDbClient.count("col-1")).thenReturn(2);
        when(chromaDbClient.getDocumentsPaged(eq("col-1"), anyInt(), eq(0))).thenReturn(Map.of(
                "ids", List.of("plein", "vide"),
                "documents", java.util.Arrays.asList("du texte", "")));
        when(chromaDbClient.getDocumentsPaged(eq("col-1"), anyInt(), eq(16))).thenReturn(Map.of(
                "ids", List.of(), "documents", List.of()));
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of(List.of(0.1f)));

        service.start("docs");
        awaitStatus(s -> "COMPLETED".equals(s.status()));

        // Seul le chunk non vide est ré-embeddé ; le chunk vide garde son vecteur existant.
        verify(chromaDbClient).updateEmbeddings(eq("col-1"),
                argThat(ids -> ids.equals(List.of("plein"))), anyList());
    }
}
