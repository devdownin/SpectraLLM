package fr.spectra.service;

import fr.spectra.persistence.StreamSourceRepository;
import org.springframework.stereotype.Service;

/** SQL is the publication pointer: staged or obsolete Kafka chunks are never context. */
@Service
public class StreamVersionVisibility {
    private final StreamSourceRepository repository;

    public StreamVersionVisibility(StreamSourceRepository repository) {
        this.repository = repository;
    }

    public boolean accepts(String sha256, String sourceFile) {
        return snapshot().test(sha256, sourceFile);
    }

    /** Freeze each source pointer for a retrieval so a concurrent publish cannot mix revisions. */
    public java.util.function.BiPredicate<String, String> snapshot() {
        java.util.Map<String, java.util.Optional<Publication>> publications = new java.util.HashMap<>();
        return (sha256, sourceFile) -> {
            if (sourceFile == null || !sourceFile.startsWith("kafka://")) return true;
            java.util.Optional<Publication> pointer = publications.computeIfAbsent(sourceFile, key ->
                    repository.findById(key).map(source -> new Publication(
                            source.getContentHash(), source.isVersionedPublication())));
            return pointer.filter(publication -> publication.hash() != null)
                    .map(publication -> (sha256 == null && !publication.versioned())
                            || publication.hash().equals(sha256)).orElse(false);
        };
    }

    private record Publication(String hash, boolean versioned) {}
}
