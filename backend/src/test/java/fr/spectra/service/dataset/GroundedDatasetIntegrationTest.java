package fr.spectra.service.dataset;

import fr.spectra.model.TrainingPair;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.LlmChatClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Les paires ancrées atteignent-elles vraiment le dataset ?
 *
 * <p>Les règles de construction sont figées par {@code GroundedPairGenerationTest} ; ce qu'on
 * vérifie ici est le <b>branchement</b>, c'est-à-dire la seule chose qu'un correctif F14 non câblé
 * laisserait passer : des helpers impeccables que la boucle de génération n'appelle jamais. C'est
 * exactement ce qui rendait le champ {@code reportPath} inutile pendant des mois.
 */
class GroundedDatasetIntegrationTest {

    @TempDir
    Path datasetDir;

    private static final List<String> CHUNKS = List.of(
            "Le péage de sortie est conforme au décret de 2019.",
            "La viabilité hivernale démarre le 15 novembre chaque année.");
    private static final List<Map<String, String>> METAS = List.of(
            Map.of("sourceFile", "peage.pdf"), Map.of("sourceFile", "hiver.pdf"));

    /**
     * Générateur branché sur un ChromaDB et un LLM de test. Le LLM rend toujours la même paire
     * Q/R : ce qui nous intéresse n'est pas ce qu'il invente, mais la forme sous laquelle la
     * paire finit dans le dataset.
     */
    private DatasetGeneratorService serviceWith(int groundedEveryN, int distractors) {
        ChromaDbClient chroma = mock(ChromaDbClient.class);
        when(chroma.getOrCreateCollection(anyString())).thenReturn("col-1");
        when(chroma.getAllDocuments("col-1")).thenReturn(Map.of(
                "documents", CHUNKS,
                "metadatas", METAS,
                "ids", List.of("id-1", "id-2")));

        LlmChatClient llm = mock(LlmChatClient.class);
        when(llm.chatJson(anyString(), anyString())).thenReturn(
                "{\"question\": \"Le péage est-il conforme ?\", \"answer\": \"Oui, il est conforme au décret.\","
                        + " \"instruction\": \"Résume le texte.\", \"summary\": \"Le péage de sortie est conforme au décret de 2019.\","
                        + " \"category\": \"technique\", \"reason\": \"péage\"}");

        return new DatasetGeneratorService(llm, chroma, mock(IngestedFileRepository.class),
                mock(fr.spectra.persistence.GenerationTaskRepository.class),
                null, datasetDir.toString(), 0, groundedEveryN, distractors, 100_000);
    }

    private List<TrainingPair> generate(int groundedEveryN, int distractors) {
        DatasetGeneratorService service = serviceWith(groundedEveryN, distractors);
        service.generate("task-1", 0);
        return service.getAllPairs();
    }

    @Test
    @DisplayName("le dataset contient les DEUX formes : nue et ancrée")
    void datasetCarriesBothShapes() {
        List<TrainingPair> pairs = generate(1, 1);

        // La forme nue reste indispensable : le mode direct (sans retrieval) existe toujours, et
        // n'entraîner que la forme ancrée le dégraderait à son tour.
        assertThat(pairs).anyMatch(p -> !p.metadata().type().endsWith("_grounded"));
        assertThat(pairs).anyMatch(p -> p.metadata().type().endsWith("_grounded"));
    }

    @Test
    @DisplayName("une paire ancrée porte le contexte et les consignes de citation du service")
    void groundedPairsCarryTheServingPrompt() {
        TrainingPair grounded = generate(1, 1).stream()
                .filter(p -> p.metadata().type().endsWith("_grounded"))
                .findFirst().orElseThrow();

        String system = grounded.conversations().get(0).content();
        assertThat(system)
                .contains("=== CONTEXTE ===")
                .contains("cite tes sources en insérant le numéro")
                .contains("[1] (Source:");
    }

    @Test
    @DisplayName("le contexte ancré contient un distracteur, pas seulement le bon passage")
    void groundedContextContainsADistractor() {
        // Sans distracteur, le contexte n'aurait qu'un passage, toujours pertinent, toujours
        // cité [1] : le modèle apprendrait la position au lieu de la lecture.
        TrainingPair grounded = generate(1, 1).stream()
                .filter(p -> p.metadata().type().endsWith("_grounded"))
                .findFirst().orElseThrow();

        assertThat(grounded.conversations().get(0).content()).contains("[2] (Source:");
    }

    @Test
    @DisplayName("à zéro, aucune paire ancrée n'est produite — le réglage est réellement branché")
    void disablingTheSettingRemovesGroundedPairs() {
        List<TrainingPair> pairs = generate(0, 2);

        assertThat(pairs).isNotEmpty();
        assertThat(pairs).noneMatch(p -> p.metadata().type().endsWith("_grounded"));
    }
}
