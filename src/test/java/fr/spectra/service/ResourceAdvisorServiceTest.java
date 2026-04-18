package fr.spectra.service;

import fr.spectra.dto.ResourceProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de ResourceAdvisorService.recommend() — calcul des paramètres llama-server.
 * La méthode est package-visible ; aucun appel système n'est effectué.
 */
class ResourceAdvisorServiceTest {

    private ResourceAdvisorService service;

    @BeforeEach
    void setUp() {
        service = new ResourceAdvisorService();
    }

    // ── Threads ───────────────────────────────────────────────────────────────

    @Test
    void recommend_chatMode_threadsIsCoreMinus2ForManyCore() {
        ResourceProfile.LlamaServerParams params = service.recommend(8, 16384, "none", 0, "chat");
        // cpuCores > 4 → threads = cpuCores - 2
        assertThat(params.threads()).isEqualTo(6);
    }

    @Test
    void recommend_chatMode_lowCoreCount_threadsAtLeastOne() {
        ResourceProfile.LlamaServerParams params = service.recommend(2, 8192, "none", 0, "chat");
        assertThat(params.threads()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void recommend_embedMode_threadsIsHalfCores() {
        ResourceProfile.LlamaServerParams params = service.recommend(8, 16384, "none", 0, "embed");
        assertThat(params.threads()).isEqualTo(4);
    }

    @Test
    void recommend_embedMode_singleCore_threadsIsOne() {
        ResourceProfile.LlamaServerParams params = service.recommend(1, 4096, "none", 0, "embed");
        assertThat(params.threads()).isEqualTo(1);
    }

    // ── Context size ──────────────────────────────────────────────────────────

    @Test
    void recommend_nvidiaHighVram_contextIs8192() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "nvidia", 8192, "chat");
        assertThat(params.contextSize()).isEqualTo(8192);
    }

    @Test
    void recommend_nvidiaMediumVram_contextIs4096() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 8192, "nvidia", 4096, "chat");
        assertThat(params.contextSize()).isEqualTo(4096);
    }

    @Test
    void recommend_highRam_contextIs4096() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 32768, "none", 0, "chat");
        assertThat(params.contextSize()).isEqualTo(4096);
    }

    @Test
    void recommend_mediumRam_contextIs2048() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "none", 0, "chat");
        assertThat(params.contextSize()).isEqualTo(2048);
    }

    @Test
    void recommend_lowRam_contextIs1024() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 8192, "none", 0, "chat");
        assertThat(params.contextSize()).isEqualTo(1024);
    }

    @Test
    void recommend_veryLowRam_contextIs512() {
        ResourceProfile.LlamaServerParams params = service.recommend(2, 4096, "none", 0, "chat");
        assertThat(params.contextSize()).isEqualTo(512);
    }

    // ── GPU layers ────────────────────────────────────────────────────────────

    @Test
    void recommend_nvidia_allGpuLayers() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "nvidia", 8192, "chat");
        assertThat(params.nGpuLayers()).isEqualTo(-1);
    }

    @Test
    void recommend_amd_allGpuLayers() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "amd", 0, "chat");
        assertThat(params.nGpuLayers()).isEqualTo(-1);
    }

    @Test
    void recommend_vulkan_partialGpuLayers() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "vulkan", 0, "chat");
        assertThat(params.nGpuLayers()).isEqualTo(20);
    }

    @Test
    void recommend_noGpu_zeroGpuLayers() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "none", 0, "chat");
        assertThat(params.nGpuLayers()).isEqualTo(0);
    }

    // ── Cache type ────────────────────────────────────────────────────────────

    @Test
    void recommend_nvidiaHighVram_cacheKIsIq4() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "nvidia", 8192, "chat");
        assertThat(params.cacheTypeK()).isEqualTo("iq4_nl");
    }

    @Test
    void recommend_noGpu_cacheKIsQ8() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "none", 0, "chat");
        assertThat(params.cacheTypeK()).isEqualTo("q8_0");
    }

    @Test
    void recommend_cacheTypeVAlwaysQ8() {
        ResourceProfile.LlamaServerParams chat  = service.recommend(4, 16384, "nvidia", 16384, "chat");
        ResourceProfile.LlamaServerParams embed = service.recommend(4, 4096,  "none",  0,     "embed");
        assertThat(chat.cacheTypeV()).isEqualTo("q8_0");
        assertThat(embed.cacheTypeV()).isEqualTo("q8_0");
    }

    // ── Flash attention ───────────────────────────────────────────────────────

    @Test
    void recommend_flashAttnAlwaysEnabled() {
        assertThat(service.recommend(4, 8192, "none",   0,    "chat").flashAttn()).isTrue();
        assertThat(service.recommend(4, 8192, "nvidia", 4096, "chat").flashAttn()).isTrue();
        assertThat(service.recommend(2, 4096, "none",   0,    "embed").flashAttn()).isTrue();
    }

    // ── CLI args ──────────────────────────────────────────────────────────────

    @Test
    void recommend_embedMode_cliContainsEmbeddingsFlag() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "none", 0, "embed");
        assertThat(params.cliArgs()).contains("--embeddings");
    }

    @Test
    void recommend_chatMode_cliDoesNotContainEmbeddingsFlag() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "none", 0, "chat");
        assertThat(params.cliArgs()).doesNotContain("--embeddings");
    }

    @Test
    void recommend_nvidia_cliContainsNGpuLayers() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "nvidia", 8192, "chat");
        assertThat(params.cliArgs()).contains("--n-gpu-layers", "-1");
    }

    @Test
    void recommend_noGpu_cliDoesNotContainNGpuLayers() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "none", 0, "chat");
        assertThat(params.cliArgs()).doesNotContain("--n-gpu-layers");
    }

    @Test
    void recommend_flashAttn_cliContainsFlashAttn() {
        ResourceProfile.LlamaServerParams params = service.recommend(4, 16384, "none", 0, "chat");
        assertThat(params.cliArgs()).contains("--flash-attn");
    }

    @Test
    void recommend_cliContainsContextAndBatchFlags() {
        ResourceProfile.LlamaServerParams params = service.recommend(8, 32768, "none", 0, "chat");
        assertThat(params.cliArgs()).contains("-c", "-b", "-t");
    }
}
