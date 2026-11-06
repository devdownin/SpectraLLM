package fr.spectra.sources;

import fr.spectra.persistence.StreamSourceRepository;
import fr.spectra.service.ChromaDbClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Read the published revision only, never a staged or failed connector update. */
@RestController
@RequestMapping("/api/sources")
public class SourcePreviewController {
    private final SourceSyncService sources;
    private final StreamSourceRepository publications;
    private final ChromaDbClient chroma;
    public SourcePreviewController(SourceSyncService sources, StreamSourceRepository publications, ChromaDbClient chroma) {
        this.sources = sources; this.publications = publications; this.chroma = chroma;
    }
    public record Preview(List<String> chunks, boolean truncated) {}
    @GetMapping("/{id}/documents/{documentId}/preview")
    public Preview preview(@PathVariable String id, @PathVariable String documentId) {
        var source = sources.get(id);
        sources.document(id, documentId);
        String hash = publications.findById("source://" + id + "/" + documentId).map(p -> p.getContentHash()).orElse(null);
        if (hash == null) return new Preview(List.of(), false);
        String collection = chroma.resolveCollectionIdUnchecked(source.collection());
        List<String> chunks = chroma.getDocumentTextsByMetadata(collection, "sha256", hash, 13);
        boolean truncated = chunks.size() > 12 || chunks.stream().anyMatch(s -> s.length() > 2000);
        return new Preview(chunks.stream().limit(12).map(s -> s.substring(0, Math.min(s.length(), 2000))).toList(), truncated);
    }
}
