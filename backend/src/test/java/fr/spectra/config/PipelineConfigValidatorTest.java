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
    void multiQueryEnabledWithZeroCount_isRejected() {
        SpectraProperties props = propsWithPipeline(pipeline(512, 64, 32));
        when(props.multiQuery()).thenReturn(new SpectraProperties.MultiQueryProperties(true, 0));

        assertThatThrownBy(() -> new PipelineConfigValidator(props, 0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multi-query.query-count");
    }

    @Test
    void longContextEnabledWithZeroMaxChunks_isRejected() {
        SpectraProperties props = propsWithPipeline(pipeline(512, 64, 32));
        when(props.longContextRag()).thenReturn(
                new SpectraProperties.LongContextRagProperties(true, 0, null));

        assertThatThrownBy(() -> new PipelineConfigValidator(props, 0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("long-context-rag.max-collection-chunks");
    }

    @Test
    void hybridEnabledWithInvalidParams_isRejected() {
        SpectraProperties props = propsWithPipeline(pipeline(512, 64, 32));
        when(props.hybridSearch()).thenReturn(
                new SpectraProperties.HybridSearchProperties(true, 0, -1.0f));

        assertThatThrownBy(() -> new PipelineConfigValidator(props, 0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hybrid-search.top-bm25")
                .hasMessageContaining("hybrid-search.bm25-weight");
    }

    @Test
    void allOptionalModulesEnabledAndValid_passes() {
        SpectraProperties props = propsWithPipeline(pipeline(512, 64, 32));
        when(props.reranker()).thenReturn(new SpectraProperties.RerankerProperties(true, null, null, null, 20));
        when(props.multiQuery()).thenReturn(new SpectraProperties.MultiQueryProperties(true, 2));
        when(props.longContextRag()).thenReturn(new SpectraProperties.LongContextRagProperties(true, 100, null));
        when(props.hybridSearch()).thenReturn(new SpectraProperties.HybridSearchProperties(true, 20, 1.0f));

        assertThatCode(() -> new PipelineConfigValidator(props, 0).validate())
                .doesNotThrowAnyException();
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
