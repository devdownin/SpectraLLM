package fr.spectra.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmFitRecommendation(
        List<ModelRecommendation> models,
        SystemSpecs system
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelRecommendation(
            String name,
            String provider,
            @JsonProperty("parameter_count") String parameterCount,
            @JsonProperty("use_case") String useCase,
            Double score,
            @JsonProperty("fit_level") String fitLevel,
            @JsonProperty("best_quant") String bestQuant,
            @JsonProperty("estimated_tps") Double estimatedTps,
            @JsonProperty("memory_required_gb") Double memoryRequiredGb,
            @JsonProperty("disk_size_gb") Double diskSizeGb,
            @JsonProperty("context_length") Integer contextLength,
            List<String> notes,
            @JsonProperty("score_components") Map<String, Double> scoreComponents,
            @JsonProperty("gguf_sources") List<GgufSource> ggufSources,
            Boolean installed,
            @JsonProperty("run_mode") String runMode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GgufSource(
            String provider,
            String repo
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SystemSpecs(
            @JsonProperty("cpu_name") String cpuName,
            @JsonProperty("cpu_cores") Integer cpuCores,
            @JsonProperty("total_ram_gb") Double totalRamGb,
            @JsonProperty("available_ram_gb") Double availableRamGb,
            @JsonProperty("gpu_name") String gpuName,
            @JsonProperty("gpu_vram_gb") Double gpuVramGb,
            @JsonProperty("has_gpu") Boolean hasGpu
    ) {}
}
