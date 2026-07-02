package fr.spectra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "spectra")
public record SpectraProperties(LlmProperties llm, ChromaDbProperties chromadb, PipelineProperties pipeline, IngestionProperties ingestion, RerankerProperties reranker, HybridSearchProperties hybridSearch, LayoutParserProperties layoutParser, AgenticRagProperties agenticRag, GedProperties ged, ConversationalRagProperties conversationalRag, CorrectiveRagProperties correctiveRag, AdaptiveRagProperties adaptiveRag, SelfRagProperties selfRag, ContextCompressionProperties contextCompression, MultiQueryProperties multiQuery, SemanticDedupProperties semanticDedup, LongContextRagProperties longContextRag, KafkaProperties kafka) {

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
    public record IngestionProperties(
            String browserlessUrl,
            Integer maxZipEntries,
            Long maxEntryBytes
    ) {
        public String effectiveBrowserlessUrl() {
            return browserlessUrl != null ? browserlessUrl : "http://browserless:3000";
        }
        public int effectiveMaxZipEntries() {
            return maxZipEntries != null ? maxZipEntries : 10_000;
        }
        public long effectiveMaxEntryBytes() {
            return maxEntryBytes != null ? maxEntryBytes : 200L * 1024 * 1024;
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
    /** Conversational RAG — reformulation de la question avec l'historique. */
    public record ConversationalRagProperties(Boolean enabled) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
    }

    /**
     * Corrective RAG — évaluation de la pertinence des chunks récupérés.
     *
     * @param enabled          activer le filtrage correctif
     * @param minRelevantChunks nombre minimum de chunks RELEVANT|AMBIGUOUS requis avant reformulation
     */
    public record CorrectiveRagProperties(Boolean enabled, Integer minRelevantChunks) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
        public int effectiveMinRelevantChunks() { return minRelevantChunks != null ? minRelevantChunks : 1; }
    }

    /**
     * Adaptive RAG — routage de la requête vers la stratégie optimale.
     *
     * @param enabled activer le classificateur de complexité
     */
    public record AdaptiveRagProperties(Boolean enabled) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
    }

    /**
     * Self-RAG — auto-évaluation de la qualité de récupération et de génération.
     *
     * @param enabled             activer la boucle de réflexion
     * @param maxReflectionIterations nombre max de tentatives de raffinement (défaut : 1)
     */
    public record SelfRagProperties(Boolean enabled, Integer maxReflectionIterations) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
        public int effectiveMaxReflectionIterations() { return maxReflectionIterations != null ? maxReflectionIterations : 1; }
    }

    /** Context Compression RAG — extraction des passages pertinents dans chaque chunk. */
    public record ContextCompressionProperties(Boolean enabled) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
    }

    /**
     * Multi-Query RAG — génération de variantes de la question pour améliorer le rappel.
     *
     * @param enabled    activer la génération de variantes
     * @param queryCount nombre de variantes à générer en plus de la question originale (défaut : 2)
     */
    public record MultiQueryProperties(Boolean enabled, Integer queryCount) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
        public int effectiveQueryCount() { return queryCount != null ? queryCount : 2; }
    }

    /**
     * Déduplication sémantique — suppression des chunks quasi-identiques après retrieval.
     *
     * @param enabled             activer la déduplication sémantique
     * @param similarityThreshold seuil de similarité Jaccard au-delà duquel deux chunks sont considérés
     *                            doublons (0.0–1.0, défaut : 0.85)
     */
    public record SemanticDedupProperties(Boolean enabled, Double similarityThreshold) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
        public double effectiveSimilarityThreshold() { return similarityThreshold != null ? similarityThreshold : 0.85; }
    }

    /**
     * Long-Context RAG — bypass du retrieval vectoriel quand le corpus tient dans la fenêtre du modèle.
     *
     * <p>Si le nombre de chunks dans la collection est inférieur ou égal à {@code maxCollectionChunks},
     * tous les chunks sont chargés directement sans recherche vectorielle. Plus économique et plus
     * simple à maintenir pour de petits corpus (&lt; 100 chunks).</p>
     *
     * @param enabled             activer le bypass long-contexte
     * @param maxCollectionChunks seuil de chunks au-delà duquel le RAG classique est utilisé (défaut : 100)
     */
    public record LongContextRagProperties(Boolean enabled, Integer maxCollectionChunks) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
        public int effectiveMaxCollectionChunks() { return maxCollectionChunks != null ? maxCollectionChunks : 100; }
    }

    /**
     * Ingestion streaming Kafka — enrichit le RAG au fil de l'eau avec des données vivantes.
     *
     * <p>Chaque message est « upserté » dans l'index : la clé du message fournit une identité
     * métier stable ({@code sourceFile = kafka://<topic>/<key>}) qui permet de remplacer la
     * version précédente (delete-by-source puis réindexation). Un message à valeur nulle
     * (tombstone log-compaction) supprime l'entrée. Désactivé par défaut : aucun bean Kafka
     * n'est créé tant que {@code enabled=false}.</p>
     *
     * @param enabled          active la consommation Kafka
     * @param bootstrapServers liste des brokers (host:port,host:port…)
     * @param topics           topics à consommer
     * @param groupId          identifiant du consumer group
     * @param collection       collection ChromaDB dédiée au flux (isole les données vivantes)
     * @param format           extension logique de routage de l'extracteur : json|txt|xml|avro
     * @param concurrency      nombre de consumers concurrents (≈ partitions traitées en //)
     * @param maxPollRecords   messages max par poll (bas : chaque message déclenche un embedding)
     * @param retentionTtlDays purge des sources non mises à jour depuis N jours (0 = désactivé)
     * @param securityProtocol PLAINTEXT | SASL_SSL | SSL | SASL_PLAINTEXT
     * @param saslMechanism    ex. SCRAM-SHA-512, PLAIN (si SASL)
     * @param saslJaasConfig   configuration JAAS complète (contient les identifiants)
     * @param contentField     (JSON) champ dont la valeur est indexée à la place du payload brut
     *                         (nom simple {@code "body"} ou pointeur JSON {@code "/data/text"}).
     *                         Vide = payload brut par défaut.
     * @param metadataFields   (JSON) champs recopiés dans les métadonnées du chunk (filtrage/traçabilité)
     */
    public record KafkaProperties(
            Boolean enabled,
            String bootstrapServers,
            List<String> topics,
            String groupId,
            String collection,
            String format,
            Integer concurrency,
            Integer maxPollRecords,
            Integer retentionTtlDays,
            String securityProtocol,
            String saslMechanism,
            String saslJaasConfig,
            String contentField,
            List<String> metadataFields
    ) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
        public String effectiveBootstrapServers() { return bootstrapServers != null ? bootstrapServers : "localhost:9092"; }
        public List<String> effectiveTopics() { return topics != null ? topics : List.of(); }
        public String effectiveGroupId() { return groupId != null ? groupId : "spectra-ingestion"; }
        public String effectiveCollection() { return collection != null ? collection : "spectra_stream"; }
        /** Extension de routage normalisée (json par défaut ; "text" → "txt"). */
        public String effectiveFormat() {
            String f = format != null ? format.trim().toLowerCase() : "json";
            return f.equals("text") ? "txt" : f;
        }
        public int effectiveConcurrency() { return concurrency != null && concurrency > 0 ? concurrency : 1; }
        public int effectiveMaxPollRecords() { return maxPollRecords != null && maxPollRecords > 0 ? maxPollRecords : 20; }
        public int effectiveRetentionTtlDays() { return retentionTtlDays != null ? retentionTtlDays : 0; }
        public String effectiveSecurityProtocol() { return securityProtocol != null ? securityProtocol : "PLAINTEXT"; }
        /** Champ de contenu configuré (mapping) ou {@code null} → payload brut. */
        public String effectiveContentField() { return contentField != null && !contentField.isBlank() ? contentField.trim() : null; }
        public List<String> effectiveMetadataFields() { return metadataFields != null ? metadataFields : List.of(); }
        /** Le mapping de champs est-il actif (extraction d'un champ JSON) ? */
        public boolean hasFieldMapping() { return effectiveContentField() != null; }
    }

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
