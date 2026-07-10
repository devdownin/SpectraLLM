package fr.spectra.service;

import fr.spectra.dto.ResourceProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Le fichier de hints {@code active-chat-params} est le contrat entre le calcul de
 * dimensionnement (Java, propriétaire unique) et l'entrypoint superviseur de llm-chat :
 * clés {@code RECO_*} en KEY=VALUE simple, consommables ligne à ligne en sh.
 */
class RuntimeParamsMaterializerTest {

    @TempDir
    Path modelsDir;

    private RuntimeParamsMaterializer materializer(int threads, int context, int batch) {
        ResourceProfile.LlamaServerParams chat = new ResourceProfile.LlamaServerParams(
                threads, context, batch, 0, false, "q8_0", "q8_0", List.of());
        ResourceProfile profile = new ResourceProfile(8, 16384, "none", 0, false, false, chat, chat);
        ResourceAdvisorService advisor = mock(ResourceAdvisorService.class);
        when(advisor.getProfile()).thenReturn(profile);
        return new RuntimeParamsMaterializer(advisor, modelsDir.toString());
    }

    @Test
    void materialize_ecritLesHintsAuFormatCleValeur() throws Exception {
        materializer(6, 4096, 1024).materialize();

        List<String> lines = Files.readAllLines(modelsDir.resolve("active-chat-params"));
        assertThat(lines)
                .contains("RECO_THREADS=6", "RECO_CONTEXT=4096", "RECO_BATCH=1024",
                        "RECO_CACHE_TYPE_K=q8_0", "RECO_CACHE_TYPE_V=q8_0");
    }

    @Test
    void materialize_reecritLeFichierApresRefresh() throws Exception {
        materializer(6, 4096, 1024).materialize();
        materializer(2, 1024, 512).materialize();

        assertThat(Files.readString(modelsDir.resolve("active-chat-params")))
                .contains("RECO_CONTEXT=1024")
                .doesNotContain("RECO_CONTEXT=4096");
    }
}
