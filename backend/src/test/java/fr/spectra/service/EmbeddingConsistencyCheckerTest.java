package fr.spectra.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rapport de cohérence embedding ↔ index : chaque collection est classée OK,
 * MISMATCH (estampille ≠ modèle actif → RAG faussé, réindexation requise) ou
 * UNSTAMPED (collection antérieure à l'estampillage, cohérence invérifiable).
 */
class EmbeddingConsistencyCheckerTest {

    private ChromaDbClient chromaDbClient;
    private ModelRegistryService modelRegistry;
    private EmbeddingConsistencyChecker checker;

    @BeforeEach
    void setUp() {
        chromaDbClient = mock(ChromaDbClient.class);
        modelRegistry = mock(ModelRegistryService.class);
        when(modelRegistry.getActiveEmbeddingModel()).thenReturn("nomic-embed-text");
        checker = new EmbeddingConsistencyChecker(chromaDbClient, modelRegistry);
    }

    @Test
    void verify_classeChaqueCollectionSelonSonEstampille() {
        when(chromaDbClient.listCollections()).thenReturn(List.of(
                Map.of("name", "coherente",
                        "metadata", Map.of(ChromaDbClient.EMBEDDING_MODEL_METADATA_KEY, "nomic-embed-text")),
                Map.of("name", "incoherente",
                        "metadata", Map.of(ChromaDbClient.EMBEDDING_MODEL_METADATA_KEY, "ancien-modele")),
                Map.of("name", "legacy",
                        "metadata", Map.of("hnsw:space", "cosine"))
        ));

        Map<String, Object> report = checker.verify();

        assertThat(report.get("activeEmbeddingModel")).isEqualTo("nomic-embed-text");
        assertThat(report.get("mismatches")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> collections = (List<Map<String, Object>>) report.get("collections");
        assertThat(collections).extracting(c -> c.get("name"), c -> c.get("status"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("coherente", "OK"),
                        org.assertj.core.groups.Tuple.tuple("incoherente", "MISMATCH"),
                        org.assertj.core.groups.Tuple.tuple("legacy", "UNSTAMPED"));

        // Le rapport indique avec quel modèle la collection incohérente a été indexée.
        assertThat(collections.get(1).get("indexedWith")).isEqualTo("ancien-modele");
    }

    @Test
    void verify_aucuneCollection_rapportVideSansMismatch() {
        when(chromaDbClient.listCollections()).thenReturn(List.of());

        Map<String, Object> report = checker.verify();

        assertThat(report.get("mismatches")).isEqualTo(0);
        assertThat((List<?>) report.get("collections")).isEmpty();
    }

    @Test
    void checkOnStartup_chromaIndisponible_neBloquePasLeDemarrage() {
        when(chromaDbClient.listCollections()).thenThrow(new RuntimeException("connexion refusée"));

        // Ne doit lever aucune exception (ChromaDB peut encore être en cours de boot).
        checker.checkOnStartup();
    }
}
