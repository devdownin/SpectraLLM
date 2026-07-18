package fr.spectra.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verrouille les défauts de {@link SpectraProperties.PipelineProperties} : ils font partie
 * du contrat opérationnel (dimensionnement mémoire, débit d'ingestion, timeouts) et une
 * régression silencieuse d'un défaut est exactement le genre de dérive déjà observée sur
 * ce dépôt (cible Java rétrogradée sans trace). Toute modification volontaire doit passer
 * par ce test ET par une entrée CHANGELOG.
 */
class SpectraPropertiesTest {

    @Test
    void pipelineProperties_nullValues_fallBackToDocumentedDefaults() {
        SpectraProperties.PipelineProperties p =
                new SpectraProperties.PipelineProperties(null, null, null, null, null, null);

        assertThat(p.chunkMaxTokens()).isEqualTo(512);
        assertThat(p.chunkOverlapTokens()).isEqualTo(64);
        // 32 : divise par 3 les allers-retours HTTP d'embedding (vs ancien défaut 10).
        assertThat(p.embeddingBatchSize()).isEqualTo(32);
        // 60 s : couvre un lot complet (32 × 512 tokens) sur CPU lent.
        assertThat(p.embeddingTimeoutSeconds()).isEqualTo(60);
        assertThat(p.generationTimeoutSeconds()).isEqualTo(120);
        assertThat(p.concurrentIngestions()).isEqualTo(4);
    }

    @Test
    void pipelineProperties_explicitValues_areKept() {
        SpectraProperties.PipelineProperties p =
                new SpectraProperties.PipelineProperties(256, 32, 8, 15, 60, 2);

        assertThat(p.chunkMaxTokens()).isEqualTo(256);
        assertThat(p.chunkOverlapTokens()).isEqualTo(32);
        assertThat(p.embeddingBatchSize()).isEqualTo(8);
        assertThat(p.embeddingTimeoutSeconds()).isEqualTo(15);
        assertThat(p.generationTimeoutSeconds()).isEqualTo(60);
        assertThat(p.concurrentIngestions()).isEqualTo(2);
    }
}
