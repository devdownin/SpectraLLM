package fr.spectra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "spectra")
public record SpectraProperties(LlmProperties llm, ChromaDbProperties chromadb, PipelineProperties pipeline, IngestionProperties ingestion, RerankerProperties reranker, HybridSearchProperties hybridSearch, LayoutParserProperties layoutParser, AgenticRagProperties agenticRag, GedProperties ged) {

    public SpectraProperties {
        if (pipeline == null) pipeline = new PipelineProperties(null, null, null, null, null, null);
        if (ged == null) ged = new GedProperties(null, null, null);
    }

    /**
     * Generic LLM config. Includes legacy flat fields (base-url, model, embedding-model)
     * used by LlmClient/EmbeddingService, plus new nested structure for llama.cpp.
     */
    public record LlmProperties(
            String baseUrl,
            String model,
            String embeddingModel,
            String provider,
            String registryPath,
            EndpointProperties chat,
            EndpointProperties embedding,
            RuntimeProperties runtime
    ) {
        public String effectiveRegistryPath() {
            return registryPath != null ? registryPath : "./data/models/registry.json";
        }
    }

    /** Endpoint config for a single llama.cpp server (chat or embedding). */
    public record EndpointProperties(
            String baseUrl,
            String model,
            Integer timeoutSeconds
    ) {
        public int effectiveTimeoutSeconds(int fallback) {
            return timeoutSeconds != null ? timeoutSeconds : fallback;
        }
    }

    /** Runtime orchestration config for a local llama-server process. */
    public record RuntimeProperties(
            boolean enabled,
            String executable,
            String host,
            String workingDir,
            int port,
            int contextSize,
            int threads,
            int parallelism,
            int startupTimeoutSeconds,
            List<String> extraArgs
    ) {
        public String effectiveExecutable() { return executable != null ? executable : "llama-server"; }
        public String effectiveHost() { return host != null ? host : "127.0.0.1"; }
        public int effectivePort() { return port > 0 ? port : 8081; }
        public String effectiveWorkingDir() { return workingDir != null ? workingDir : "."; }
        public int effectiveContextSize() { return contextSize; }
        public int effectiveThreads() { return threads; }
        public int effectiveParallelism() { return parallelism > 0 ? parallelism : 1; }
        public int effectiveStartupTimeoutSeconds() { return startupTimeoutSeconds > 0 ? startupTimeoutSeconds : 60; }
        public List<String> effectiveExtraArgs() { return extraArgs != null ? extraArgs : List.of(); }
    }

    public record ChromaDbProperties(String baseUrl, String collection) {
        public String effectiveCollection() {
            return collection != null ? collection : "spectra_documents";
        }
    }

    /** Ingestion pipeline configuration (URL fetcher, browserless, etc.). */
    public record IngestionProperties(String browserlessUrl) {
        public String effectiveBrowserlessUrl() {
            return browserlessUrl != null ? browserlessUrl : "http://browserless:3000";
        }
    }

    public record LayoutParserProperties(
            Boolean enabled,
            String baseUrl,
            Integer timeoutSeconds,
            Integer bufferSizeMb
    ) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
        public String effectiveBaseUrl() { return baseUrl != null ? baseUrl : "http://docparser:8001"; }
        public int effectiveTimeoutSeconds() { return timeoutSeconds != null ? timeoutSeconds : 120; }
        /** Taille max du buffer in-memory WebClient pour les uploads PDF (doit être ≥ max-file-size). */
        public int effectiveBufferSizeMb() { return bufferSizeMb != null ? bufferSizeMb : 100; }
    }

    public record HybridSearchProperties(
            Boolean enabled,
            Integer topBm25,
            Float bm25Weight
    ) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
        public int effectiveTopBm25() { return topBm25 != null ? topBm25 : 20; }
        public float effectiveBm25Weight() { return bm25Weight != null ? bm25Weight : 1.0f; }
    }

    public record RerankerProperties(
            Boolean enabled,
            String baseUrl,
            String model,
            Integer timeoutSeconds,
            Integer topCandidates
    ) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
        public String effectiveBaseUrl() { return baseUrl != null ? baseUrl : "http://reranker:8000"; }
        public String effectiveModel() { return model != null ? model : "cross-encoder/ms-marco-MiniLM-L-6-v2"; }
        public int effectiveTimeoutSeconds() { return timeoutSeconds != null ? timeoutSeconds : 30; }
        public int effectiveTopCandidates() { return topCandidates != null ? topCandidates : 20; }
    }

    public record AgenticRagProperties(
            Boolean enabled,
            Integer maxIterations,
            Integer initialTopK,
            String responseLanguage,
            Integer maxContextTokens
    ) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
        /** Max tours de recherche complémentaire avant réponse forcée. */
        public int effectiveMaxIterations() { return maxIterations != null ? maxIterations : 3; }
        /** Nombre de chunks récupérés lors du retrieval initial. */
        public int effectiveInitialTopK() { return initialTopK != null ? initialTopK : 5; }
        /**
         * Langue de réponse : "fr" (français forcé), "en" (anglais forcé), "auto" (langue de la question).
         * Défaut : "fr".
         */
        public String effectiveResponseLanguage() { return responseLanguage != null ? responseLanguage : "fr"; }
        /**
         * Budget maximum de tokens de contexte avant appel LLM (heuristique : 1 token ≈ 4 chars).
         * Défaut : 3000 (laisse de la marge sur un modèle 4 k).
         */
        public int effectiveMaxContextTokens() { return maxContextTokens != null ? maxContextTokens : 3000; }
    }

    public record PipelineProperties(
            Integer chunkMaxTokens,
            Integer chunkOverlapTokens,
            Integer embeddingBatchSize,
            Integer embeddingTimeoutSeconds,
            Integer generationTimeoutSeconds,
            Integer concurrentIngestions
    ) {
        public PipelineProperties {
            if (chunkMaxTokens == null) chunkMaxTokens = 512;
            if (chunkOverlapTokens == null) chunkOverlapTokens = 64;
            if (embeddingBatchSize == null) embeddingBatchSize = 10;
            if (embeddingTimeoutSeconds == null) embeddingTimeoutSeconds = 30;
            if (generationTimeoutSeconds == null) generationTimeoutSeconds = 120;
            if (concurrentIngestions == null) concurrentIngestions = 4;
        }
    }

    /**
     * Configuration GED.
     *
     * @param archiveDir          répertoire d'archivage des manifests
     * @param autoQualifyThreshold seuil de score qualité pour auto-qualification (0.0 = désactivé)
     * @param retentionProperties politiques de rétention
     */
    public record GedProperties(
            String archiveDir,
            Double autoQualifyThreshold,
            RetentionProperties retention
    ) {
        public String effectiveArchiveDir() {
            return archiveDir != null ? archiveDir : "./data/archive";
        }

        /** Seuil d'auto-qualification : 0.0 = désactivé, sinon 0.0 < seuil ≤ 1.0. */
        public double effectiveAutoQualifyThreshold() {
            return autoQualifyThreshold != null ? autoQualifyThreshold : 0.0;
        }

        public record RetentionProperties(Integer archiveAfterDays, Integer purgeAfterDays) {
            public int effectiveArchiveAfterDays() { return archiveAfterDays != null ? archiveAfterDays : 0; }
            public int effectivePurgeAfterDays()   { return purgeAfterDays   != null ? purgeAfterDays   : 0; }
        }
    }
}
