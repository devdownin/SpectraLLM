package fr.spectra.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Validation fail-fast de la configuration : les combinaisons incohérentes empêchent le
 * démarrage avec un message clair ; une configuration par défaut passe sans bruit.
 */
class PipelineConfigValidatorTest {

    private static SpectraProperties propsWithPipeline(SpectraProperties.PipelineProperties pipeline) {
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);
        // Modules optionnels désactivés par défaut dans ces tests (records null → ignorés).
        return props;
    }

    private static SpectraProperties.PipelineProperties pipeline(int max, int overlap, int batch) {
        // chunkMaxTokens, chunkOverlapTokens, embeddingBatchSize, timeouts, concurrency.
        return new SpectraProperties.PipelineProperties(max, overlap, batch, 60, 120, 4);
    }

    @Test
    void defaultConfig_passes() {
        SpectraProperties props = propsWithPipeline(pipeline(512, 64, 32));

        assertThatCode(() -> new PipelineConfigValidator(props, 0).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void overlapGreaterThanOrEqualToChunkSize_isRejected() {
        SpectraProperties props = propsWithPipeline(pipeline(512, 512, 32));

        assertThatThrownBy(() -> new PipelineConfigValidator(props, 0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chunk-overlap-tokens")
                .hasMessageContaining("ne progresse pas");
    }

    @Test
    void nonPositiveEmbeddingBatchSize_isRejected() {
        SpectraProperties props = propsWithPipeline(pipeline(512, 64, 0));

        assertThatThrownBy(() -> new PipelineConfigValidator(props, 0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding-batch-size");
    }

    @Test
    void negativeMaxActiveIngestions_isRejected() {
        SpectraProperties props = propsWithPipeline(pipeline(512, 64, 32));

        assertThatThrownBy(() -> new PipelineConfigValidator(props, -1).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-active-ingestions");
    }

    @Test
    void rerankerEnabledWithZeroCandidates_isRejected() {
        SpectraProperties props = propsWithPipeline(pipeline(512, 64, 32));
        when(props.reranker()).thenReturn(new SpectraProperties.RerankerProperties(
                true, null, null, null, 0));

        assertThatThrownBy(() -> new PipelineConfigValidator(props, 0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-candidates");
    }

    @Test
    void allErrorsAreReportedTogether() {
        SpectraProperties props = propsWithPipeline(pipeline(512, 600, 0));

        assertThatThrownBy(() -> new PipelineConfigValidator(props, 0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chunk-overlap-tokens")
                .hasMessageContaining("embedding-batch-size");
    }
}
