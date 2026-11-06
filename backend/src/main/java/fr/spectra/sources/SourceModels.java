package fr.spectra.sources;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** Public connector contract. Credentials are environment references, never secret values. */
public final class SourceModels {
    private SourceModels() {}
    public enum Type { LOCAL_FOLDER, S3, WEB }
    public enum Status { IDLE, RUNNING, SUCCESS, PARTIAL, FAILED, INTERRUPTED, CANCELLED }

    public record Config(
            @Size(max=1024) String rootPath, @Size(max=2048) String url, @Size(max=2048) String sitemapUrl,
            @Size(max=255) String bucket, @Size(max=1024) String prefix, @Size(max=2048) String endpoint,
            @Size(max=64) String region, @Pattern(regexp="[A-Z_][A-Z0-9_]{0,127}|^$") String accessKeyEnv,
            @Pattern(regexp="[A-Z_][A-Z0-9_]{0,127}|^$") String secretKeyEnv,
            @Pattern(regexp="[A-Z_][A-Z0-9_]{0,127}|^$") String sessionTokenEnv,
            @Size(max=30) List<@Pattern(regexp="\\.?[a-zA-Z0-9]{1,16}") String> extensions,
            @Size(max=30) List<@Size(max=200) String> excludePatterns,
            @Min(1) @Max(10000) Integer maxDocuments, @Min(0) @Max(8) Integer maxDepth,
            @Min(1) @Max(50) Integer maxFileSizeMb) {
        public Config {
            extensions = extensions == null ? List.of() : List.copyOf(extensions);
            excludePatterns = excludePatterns == null ? List.of() : List.copyOf(excludePatterns);
            maxDocuments = maxDocuments == null ? 1000 : maxDocuments;
            maxDepth = maxDepth == null ? 2 : maxDepth;
            maxFileSizeMb = maxFileSizeMb == null ? 25 : maxFileSizeMb;
        }
        public long maxBytes() { return maxFileSizeMb * 1024L * 1024L; }
    }

    public record Request(@NotBlank @Size(max=120) String name, @NotNull Type type,
                          @NotBlank @Pattern(regexp="[a-zA-Z0-9][a-zA-Z0-9_-]{2,62}") String collection,
                          boolean enabled, @Min(0) @Max(10080) int intervalMinutes,
                          @NotNull @Valid Config config) {}
    public record Catalog(Type type, String name, String icon, boolean available, List<String> capabilities) {}
    public record ConnectionTest(boolean success, String message, int sampleCount) {}
    public record Run(String id, String sourceId, Status status, int scanned, int indexed, int unchanged,
                      int deleted, int failed, Instant startedAt, Instant completedAt, String error) {}
    public record Source(String id, String name, Type type, String collection, boolean enabled,
                         int intervalMinutes, Config config, Status status, long documentCount,
                         Instant lastSuccessAt, Instant nextSyncAt, String lastError, Run lastRun) {}
    public record Document(String id, String externalId, String title, String sourceUrl, String revision,
                           Instant indexedAt, Instant lastSeenAt, String status, String error) {}
}
