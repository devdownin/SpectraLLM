package fr.spectra.sources;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/sources")
public class SourceController {
    private final SourceSyncService sources;
    public SourceController(SourceSyncService sources) { this.sources = sources; }
    @GetMapping public List<SourceModels.Source> list() { return sources.list(); }
    @GetMapping("/catalog") public List<SourceModels.Catalog> catalog() { return sources.catalog(); }
    @PostMapping("/test") public SourceModels.ConnectionTest test(@Valid @RequestBody SourceModels.Request request) { return sources.test(request); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public SourceModels.Source create(@Valid @RequestBody SourceModels.Request request) { return sources.create(request); }
    @GetMapping("/{id}") public SourceModels.Source get(@PathVariable String id) { return sources.get(id); }
    @PutMapping("/{id}") public SourceModels.Source update(@PathVariable String id, @Valid @RequestBody SourceModels.Request request) { return sources.update(id, request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { sources.delete(id); }
    @PostMapping("/{id}/test") public SourceModels.ConnectionTest test(@PathVariable String id) { return sources.test(id); }
    @PostMapping("/{id}/sync") @ResponseStatus(HttpStatus.ACCEPTED) public SourceModels.Run sync(@PathVariable String id) { return sources.sync(id); }
    @PostMapping("/{id}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT) public void cancel(@PathVariable String id) { sources.cancel(id); }
    @GetMapping("/{id}/runs") public List<SourceModels.Run> runs(@PathVariable String id) { return sources.history(id); }
    @GetMapping("/{id}/documents") public Page<SourceModels.Document> documents(@PathVariable String id,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) { return sources.documents(id, page, size); }
    @GetMapping("/{id}/documents/{documentId}") public SourceModels.Document document(@PathVariable String id,
            @PathVariable String documentId) { return sources.document(id, documentId); }
}
