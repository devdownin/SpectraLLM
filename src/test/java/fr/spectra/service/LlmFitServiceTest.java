package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.LlmFitRecommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LlmFitServiceTest {

    private LlmFitService llmFitService;
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ModelRegistryService modelRegistryService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        llmFitService = new LlmFitService(objectMapper, modelRegistryService);
    }

    @Test
    void isGgufCompatible_withGgufInName_returnsTrue() {
        LlmFitRecommendation.ModelRecommendation model = createModel("QuantFactory/Llama3-GGUF", null, "Q4_K_M");
        boolean result = invokeIsGgufCompatible(model);
        assertThat(result).isTrue();
    }

    @Test
    void isGgufCompatible_withGgufSources_returnsTrue() {
        LlmFitRecommendation.GgufSource source = new LlmFitRecommendation.GgufSource("QuantFactory", "QuantFactory/Llama3-GGUF");
        LlmFitRecommendation.ModelRecommendation model = createModel("Meta/Llama3", List.of(source), "None");
        boolean result = invokeIsGgufCompatible(model);
        assertThat(result).isTrue();
    }

    @Test
    void isGgufCompatible_withGgufQuantButNoGgufInName_returnsFalse() {
        // Even if it has a GGUF quant, we don't trust base repos without "GGUF" in name or sources
        LlmFitRecommendation.ModelRecommendation model = createModel("Meta/Llama3", null, "Q4_K_M");
        boolean result = invokeIsGgufCompatible(model);
        assertThat(result).isFalse();
    }

    @Test
    void isGgufCompatible_withF16QuantButNoGgufInName_returnsFalse() {
        LlmFitRecommendation.ModelRecommendation model = createModel("Meta/Llama3", null, "F16");
        boolean result = invokeIsGgufCompatible(model);
        assertThat(result).isFalse();
    }

    @Test
    void isGgufCompatible_withDeepSeekOCR_returnsFalse() {
        // This is the latest model that failed in the user's report
        LlmFitRecommendation.ModelRecommendation model = createModel("deepseek-ai/DeepSeek-OCR-2", null, "Q4_K_M");
        boolean result = invokeIsGgufCompatible(model);
        assertThat(result).isFalse();
    }

    @Test
    void isGgufCompatible_withNonGgufSuffix_returnsFalse() {
        // This is the model that failed in the user's report
        LlmFitRecommendation.ModelRecommendation model = createModel("RedHatAI/Qwen3-30B-A3B-Instruct-2507-quantized.w4a16", null, "Q6_K");
        boolean result = invokeIsGgufCompatible(model);
        assertThat(result).isFalse();
    }

    @Test
    void isGgufCompatible_withNonGgufSuffixButGgufSources_returnsTrue() {
        LlmFitRecommendation.GgufSource source = new LlmFitRecommendation.GgufSource("QuantFactory", "QuantFactory/Llama3-GGUF");
        LlmFitRecommendation.ModelRecommendation model = createModel("Meta/Llama3-AWQ", List.of(source), "Q4_K_M");
        boolean result = invokeIsGgufCompatible(model);
        assertThat(result).isTrue();
    }

    private LlmFitRecommendation.ModelRecommendation createModel(String name, List<LlmFitRecommendation.GgufSource> sources, String quant) {
        return new LlmFitRecommendation.ModelRecommendation(
                name, null, "Author", "8B", "chat", 0.9, "perfect", quant,
                10.0, 5.0, 4.0, 4096, List.of(), Map.of(), sources, false, "GPU"
        );
    }

    private boolean invokeIsGgufCompatible(LlmFitRecommendation.ModelRecommendation model) {
        try {
            var method = LlmFitService.class.getDeclaredMethod("isGgufCompatible", LlmFitRecommendation.ModelRecommendation.class);
            method.setAccessible(true);
            return (boolean) method.invoke(llmFitService, model);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
