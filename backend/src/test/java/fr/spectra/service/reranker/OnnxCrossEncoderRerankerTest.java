package fr.spectra.service.reranker;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ServiceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comportement du moteur ONNX en dehors de l'inférence : configuration, dégradation, contrat.
 *
 * <p>L'appel à {@code OrtSession.run} demande un vrai artefact de modèle (~0,5 Go) et n'est donc
 * pas exercé ici — il l'est par le harnais de parité contre la référence capturée
 * ({@link RerankerParityTest}). Ce qui est vérifié ici, c'est tout le reste : ce qui se passe
 * quand le modèle manque, et le contrat d'appel que le service Python remplissait.
 *
 * <p>Deux régimes distincts, et c'est délibéré :
 * <ul>
 *   <li><b>modèle absent</b> = problème d'approvisionnement → dégradation visible (l'application
 *       démarre, {@code /api/status} rapporte l'indisponibilité et sa cause, {@code rerank} lève
 *       et {@code RagService} retombe sur l'ordre vectoriel avec {@code rerankApplied=false}) ;</li>
 *   <li><b>configuration invalide</b> = erreur de l'opérateur → échec immédiat, comme
 *       {@code PipelineConfigValidator}. Retomber en silence sur un barème par défaut changerait
 *       l'échelle des scores publiés sans que rien ne l'indique.</li>
 * </ul>
 */
class OnnxCrossEncoderRerankerTest {

    private static SpectraProperties props(String modelPath, String activation) {
        SpectraProperties p = mock(SpectraProperties.class);
        when(p.reranker()).thenReturn(new SpectraProperties.RerankerProperties(
                true, null, null, null, 20, "onnx",
                new SpectraProperties.OnnxRerankerProperties(modelPath, activation, 512, 16)));
        return p;
    }

    // ── Modèle absent : dégradation visible, pas d'échec au démarrage ──────────────────────

    @Test
    void modeleAbsent_leBeanSeCreeEtRapporteLIndisponibilite(@TempDir Path dir) {
        try (OnnxCrossEncoderReranker reranker =
                     new OnnxCrossEncoderReranker(props(dir.toString(), "sigmoid"))) {

            ServiceStatus status = reranker.checkHealth();

            assertThat(reranker.isLoaded()).isFalse();
            assertThat(status.available()).isFalse();
            assertThat(status.name()).isEqualTo("reranker");
            assertThat(status.details()).containsEntry("engine", "onnx");
            // La cause doit être actionnable : quels fichiers, dans quel répertoire.
            assertThat(String.valueOf(status.details().get("error")))
                    .contains("model.onnx").contains("tokenizer.json").contains(dir.toString());
        }
    }

    @Test
    void modeleAbsent_rerankLeveEtLaisseRagServiceRetomber(@TempDir Path dir) {
        try (OnnxCrossEncoderReranker reranker =
                     new OnnxCrossEncoderReranker(props(dir.toString(), "sigmoid"))) {

            // Une exception — jamais un classement identité à scores nuls, qui ferait publier
            // rerankApplied=true avec des métadonnées fausses.
            assertThatThrownBy(() -> reranker.rerank("q", List.of("a", "b"), 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("non chargé");
        }
    }

    @Test
    void tokenizerPresentMaisModeleAbsent_egalementDegrade(@TempDir Path dir) throws IOException {
        // Un artefact partiellement déposé est un cas réel de provisionnement raté.
        Files.writeString(dir.resolve(OnnxCrossEncoderReranker.TOKENIZER_FILE), "{}",
                StandardCharsets.UTF_8);

        try (OnnxCrossEncoderReranker reranker =
                     new OnnxCrossEncoderReranker(props(dir.toString(), "sigmoid"))) {
            assertThat(reranker.isLoaded()).isFalse();
            assertThat(reranker.checkHealth().available()).isFalse();
        }
    }

    // ── Contrat d'appel, indépendant du chargement ─────────────────────────────────────────

    @Test
    void vivierVide_resultatVide(@TempDir Path dir) {
        // Même contrat que le client HTTP : rien à classer n'est pas une anomalie.
        try (OnnxCrossEncoderReranker reranker =
                     new OnnxCrossEncoderReranker(props(dir.toString(), "sigmoid"))) {
            assertThat(reranker.rerank("q", List.of(), 5)).isEmpty();
        }
    }

    @Test
    void topNInvalide_rejete(@TempDir Path dir) {
        // Le service Python rejetait en 422 (Pydantic Field(ge=1)) ; le moteur doit refuser de
        // même, et non renvoyer un sous-ensemble arbitraire.
        try (OnnxCrossEncoderReranker reranker =
                     new OnnxCrossEncoderReranker(props(dir.toString(), "sigmoid"))) {
            assertThatThrownBy(() -> reranker.rerank("q", List.of("a"), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("top_n");
            assertThatThrownBy(() -> reranker.rerank("q", List.of("a"), -3))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── Configuration ─────────────────────────────────────────────────────────────────────

    @Test
    void activationInvalide_echecImmediat(@TempDir Path dir) {
        assertThatThrownBy(() -> new OnnxCrossEncoderReranker(props(dir.toString(), "softmax")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("softmax");
    }

    @Test
    void defautsAppliquesQuandLaSectionOnnxEstAbsente() {
        SpectraProperties p = mock(SpectraProperties.class);
        when(p.reranker()).thenReturn(new SpectraProperties.RerankerProperties(
                true, null, null, null, 20, "onnx", null));

        // Ne doit pas lever : les défauts (./data/models/reranker, sigmoid, 512, 16) s'appliquent,
        // et l'absence d'artefact dégrade proprement.
        try (OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(p)) {
            assertThat(reranker.isLoaded()).isFalse();
            assertThat(reranker.checkHealth().url()).endsWith("data/models/reranker");
        }
    }

    @Test
    void fermetureIdempotente(@TempDir Path dir) {
        OnnxCrossEncoderReranker reranker =
                new OnnxCrossEncoderReranker(props(dir.toString(), "sigmoid"));

        reranker.close();
        reranker.close();   // @PreDestroy peut s'ajouter à un close() explicite
    }

    // ── Propriétés de configuration ───────────────────────────────────────────────────────

    @Test
    void moteurParDefautHttp_leMoteurOnnxResteOptIn() {
        // Garde-fou : basculer le défaut sur onnx casserait toute installation existante ayant
        // SPECTRA_RERANKER_ENABLED=true mais aucun artefact local.
        SpectraProperties.RerankerProperties defaults =
                new SpectraProperties.RerankerProperties(true, null, null, null, null, null, null);

        assertThat(defaults.effectiveEngine()).isEqualTo("http");
        assertThat(defaults.effectiveOnnx().effectiveActivation()).isEqualTo("sigmoid");
        assertThat(defaults.effectiveOnnx().effectiveModelPath()).isEqualTo("./data/models/reranker");
        assertThat(defaults.effectiveOnnx().effectiveMaxLength()).isEqualTo(512);
        assertThat(defaults.effectiveOnnx().effectiveBatchSize()).isEqualTo(16);
    }
}
