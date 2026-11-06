package fr.spectra.sources;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.service.IngestionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SourceSyncService {
    private final SourceInstanceRepository sources;
    private final SourceDocumentRepository documents;
    private final SourceRunRepository runs;
    private final IngestionService ingestion;
    private final Map<SourceModels.Type, SourceConnector> connectors;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, AtomicBoolean> active = new ConcurrentHashMap<>();
    private final Semaphore slots;

    public SourceSyncService(SourceInstanceRepository sources, SourceDocumentRepository documents,
                             SourceRunRepository runs, IngestionService ingestion, List<SourceConnector> connectors,
                             @Value("${spectra.sources.max-concurrent:2}") int maxConcurrent) {
        this.sources = sources; this.documents = documents; this.runs = runs; this.ingestion = ingestion;
        this.connectors = new EnumMap<>(SourceModels.Type.class);
        connectors.forEach(c -> this.connectors.put(c.type(), c));
        slots = new Semaphore(Math.max(1, Math.min(8, maxConcurrent)));
    }
    @PostConstruct public void recover() {
        for (SourceRun run : runs.findByStatus(SourceModels.Status.RUNNING)) {
            run.status = SourceModels.Status.INTERRUPTED; run.completedAt = Instant.now();
            run.error = "Synchronization interrupted by a server restart. Indexed document checkpoints are preserved.";
            runs.saveAndFlush(run);
        }
        for (SourceInstance source : sources.findAllByOrderByNameAsc()) {
            if (source.status == SourceModels.Status.RUNNING) {
                source.status = SourceModels.Status.INTERRUPTED;
                source.lastError = "Synchronization interrupted. Run it again to resume.";
                source.nextSyncAt = next(source); sources.saveAndFlush(source);
            }
        }
    }
    public List<SourceModels.Catalog> catalog() {
        return List.of(new SourceModels.Catalog(SourceModels.Type.LOCAL_FOLDER, "Local folder", "folder_open", true, List.of("scheduled", "recursive", "exclusions")),
                new SourceModels.Catalog(SourceModels.Type.S3, "S3 / MinIO", "cloud", true, List.of("scheduled", "pagination", "prefix")),
                new SourceModels.Catalog(SourceModels.Type.WEB, "Website / sitemap", "language", true, List.of("scheduled", "sitemap", "robots")));
    }
    public List<SourceModels.Source> list() { return sources.findAllByOrderByNameAsc().stream().map(this::dto).toList(); }
    public SourceModels.Source get(String id) { return dto(source(id)); }
    public synchronized SourceModels.Source create(SourceModels.Request request) {
        validate(request);
        SourceInstance source = new SourceInstance(); source.id = UUID.randomUUID().toString();
        apply(source, request); source.status = SourceModels.Status.IDLE;
        // The first run is explicit, allowing review after a connection test and before indexing.
        source.nextSyncAt = next(source);
        return dto(sources.saveAndFlush(source));
    }
    public synchronized SourceModels.Source update(String id, SourceModels.Request request) {
        SourceInstance source = source(id); idle(source); validate(request);
        if (source.type != request.type() || !source.collection.equals(request.collection()))
            throw new IllegalArgumentException("Connector type and collection cannot change. Create a new source instead.");
        apply(source, request); source.nextSyncAt = next(source);
        return dto(sources.saveAndFlush(source));
    }
    public SourceModels.ConnectionTest test(SourceModels.Request request) {
        validate(request);
        try { return connector(request.type()).test(request.config()); }
        catch (Exception e) { return new SourceModels.ConnectionTest(false, SourceSupport.failure(e), 0); }
    }
    public SourceModels.ConnectionTest test(String id) {
        SourceInstance s = source(id);
        return test(new SourceModels.Request(s.name, s.type, s.collection, s.enabled, s.intervalMinutes, config(s)));
    }
    public synchronized SourceModels.Run sync(String id) {
        SourceInstance source = source(id); idle(source);
        if (!source.enabled) throw new ResponseStatusException(HttpStatus.CONFLICT, "Enable the source before synchronizing");
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Other sources are synchronizing. Try again shortly.");
        AtomicBoolean cancelled = new AtomicBoolean();
        SourceRun run = new SourceRun(); run.id = UUID.randomUUID().toString(); run.sourceId = id;
        run.status = SourceModels.Status.RUNNING; run.startedAt = Instant.now();
        try {
            runs.saveAndFlush(run);
            source.status = SourceModels.Status.RUNNING; source.lastError = null; source.nextSyncAt = null;
            SourceInstance runningSource = sources.saveAndFlush(source); active.put(id, cancelled);
            SourceModels.Run started = run.toDto();
            executor.submit(() -> execute(runningSource, run, cancelled));
            return started;
        } catch (RuntimeException e) {
            active.remove(id); slots.release();
            run.status = SourceModels.Status.FAILED; run.completedAt = Instant.now(); run.error = "Synchronization could not start";
            runs.saveAndFlush(run);
            SourceInstance failedSource = source(id);
            failedSource.status = SourceModels.Status.FAILED; failedSource.lastError = run.error; failedSource.nextSyncAt = next(failedSource);
            sources.saveAndFlush(failedSource); throw e;
        }
    }
    public synchronized void cancel(String id) {
        source(id);
        AtomicBoolean flag = active.get(id);
        if (flag != null) flag.set(true);
    }
    public List<SourceModels.Run> history(String id) {
        source(id); return runs.findTop20BySourceIdOrderByStartedAtDesc(id).stream().map(SourceRun::toDto).toList();
    }
    public Page<SourceModels.Document> documents(String id, int page, int size) {
        source(id); return documents.findBySourceIdOrderByTitleAsc(id, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)))).map(SourceDocument::toDto);
    }
    public SourceModels.Document document(String sourceId, String documentId) {
        source(sourceId);
        return documents.findById(documentId).filter(d -> d.sourceId.equals(sourceId))
                .map(SourceDocument::toDto).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source document not found"));
    }
    public synchronized void delete(String id) {
        SourceInstance source = source(id); idle(source);
        // Disable first: a partial cleanup is visible and can be safely retried.
        source.enabled = false; source.nextSyncAt = null; source = sources.saveAndFlush(source);
        try {
            for (SourceDocument document : documents.findBySourceId(id)) {
                ingestion.purgeStreamSource(document.sourceKey(), source.collection);
                documents.deleteById(document.id);
            }
            for (SourceRun run : runs.findAll()) if (run.sourceId.equals(id)) runs.deleteById(run.id);
            sources.deleteById(id);
        } catch (RuntimeException e) {
            source.status = SourceModels.Status.FAILED; source.lastError = "Source cleanup was incomplete. Retry deletion.";
            sources.saveAndFlush(source);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, source.lastError);
        }
    }
    @Scheduled(fixedDelayString="${spectra.sources.scheduler-delay-ms:30000}", initialDelayString="${spectra.sources.scheduler-delay-ms:30000}")
    public void scheduled() {
        for (SourceInstance source : sources.findAllByOrderByNameAsc()) {
            if (source.status == SourceModels.Status.RUNNING && !active.containsKey(source.id)) {
                synchronized (this) {
                    SourceInstance fresh = source(source.id);
                    if (fresh.status == SourceModels.Status.RUNNING && !active.containsKey(fresh.id)) {
                        idle(fresh); fresh.lastError = "Previous synchronization was interrupted. Retry to resume.";
                        fresh.nextSyncAt = next(fresh); sources.saveAndFlush(fresh);
                    }
                }
                continue;
            }
            if (source.enabled && source.intervalMinutes > 0 && source.status != SourceModels.Status.RUNNING
                    && source.nextSyncAt != null && !source.nextSyncAt.isAfter(Instant.now())) {
                try { sync(source.id); }
                catch (ResponseStatusException e) { if (e.getStatusCode().value() != 409 && e.getStatusCode().value() != 429) throw e; }
            }
        }
    }
    private void execute(SourceInstance source, SourceRun run, AtomicBoolean cancelled) {
        SourceModels.Config config = config(source);
        try {
            boolean complete = connector(source.type).scan(config, new SourceConnector.Sink() {
                @Override public void checkCancelled() throws InterruptedException {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Synchronization cancelled");
                }
                @Override public void accept(SourceConnector.Item item) throws Exception {
                    checkCancelled(); run.scanned++;
                    String id = SourceSupport.hash(source.id + "\n" + item.externalId());
                    SourceDocument document = documents.findById(id).orElseGet(SourceDocument::new);
                    document.id = id; document.sourceId = source.id; document.externalId = item.externalId();
                    document.title = SourceSupport.bounded(item.title(), 512); document.sourceUrl = item.sourceUrl();
                    document.lastSeenAt = Instant.now(); document.seenRun = run.id;
                    // Track the identity durably before publication: a crash must never create an
                    // indexed source that reconciliation or connector deletion cannot enumerate.
                    document.status = "INDEXING";
                    documents.saveAndFlush(document);
                    try {
                        var metadata = new TreeMap<>(item.metadata());
                        metadata.put("sourceInstanceId", source.id); metadata.put("connectorType", source.type.name());
                        metadata.put("externalId", item.externalId()); metadata.put("title", document.title);
                        if (item.sourceUrl() != null) metadata.put("sourceUrl", item.sourceUrl());
                        String fingerprint = SourceSupport.hash(SourceSupport.hash(item.content()) + "\n" + json.writeValueAsString(metadata));
                        if (fingerprint.equals(document.fingerprint) && document.indexedAt != null) {
                            run.unchanged++;
                        } else {
                            if (item.content().length == 0) throw new SourceSupport.SourceProblem("Source document is empty. The last indexed version was kept.");
                            var result = ingestion.upsertFromStream(document.sourceKey(), item.filename(),
                                    new ByteArrayInputStream(item.content()), source.collection, null, metadata);
                            if (result.kind() == IngestionService.UpsertResult.Kind.DELETED)
                                throw new SourceSupport.SourceProblem("Source document produced no searchable content");
                            document.fingerprint = fingerprint; document.revision = item.revision(); document.indexedAt = Instant.now(); run.indexed++;
                        }
                        document.status = "INDEXED"; document.error = null;
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) throw e;
                        document.status = "FAILED"; document.error = SourceSupport.failure(e); run.failed++;
                    }
                    documents.saveAndFlush(document); runs.saveAndFlush(run);
                }
                @Override public void deleted(String externalId) throws Exception {
                    checkCancelled();
                    var doc = documents.findById(SourceSupport.hash(source.id + "\n" + externalId));
                    if (doc.isPresent()) {
                        ingestion.purgeStreamSource(doc.get().sourceKey(), source.collection);
                        documents.deleteById(doc.get().id); run.deleted++; runs.saveAndFlush(run);
                    }
                }
            });
            if (cancelled.get()) throw new InterruptedException();
            if (complete && run.failed == 0) {
                // Seen-run checkpoints survive restarts. Only a complete successful listing may reconcile absence.
                while (true) {
                    var stale = documents.findBySourceIdAndSeenRunNot(source.id, run.id, PageRequest.of(0, 100));
                    if (stale.isEmpty()) break;
                    for (SourceDocument document : stale) {
                        if (cancelled.get()) throw new InterruptedException();
                        ingestion.purgeStreamSource(document.sourceKey(), source.collection);
                        documents.deleteById(document.id); run.deleted++; runs.saveAndFlush(run);
                    }
                }
            }
            run.status = complete && run.failed == 0 ? SourceModels.Status.SUCCESS : SourceModels.Status.PARTIAL;
            if (!complete) run.error = "Discovery limit reached. Increase the document limit or narrow the scope. Missing documents were kept.";
            else if (run.failed > 0) run.error = "Some documents failed to index. Their published versions and all missing documents were kept.";
        } catch (Exception e) {
            boolean stopped = cancelled.get() || e instanceof InterruptedException || e instanceof java.io.InterruptedIOException;
            run.status = stopped ? SourceModels.Status.CANCELLED : run.scanned > 0 ? SourceModels.Status.PARTIAL : SourceModels.Status.FAILED;
            run.error = stopped ? "Synchronization cancelled. Published documents were kept." : SourceSupport.failure(e);
            if (!stopped) run.failed++;
        } finally {
            try {
                run.completedAt = Instant.now(); runs.saveAndFlush(run);
                source.status = run.status; source.lastError = run.error;
                if (run.status == SourceModels.Status.SUCCESS) source.lastSuccessAt = run.completedAt;
                source.nextSyncAt = next(source); sources.saveAndFlush(source);
            } finally { active.remove(source.id); slots.release(); }
        }
    }
    private SourceInstance source(String id) {
        return sources.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));
    }
    private void idle(SourceInstance source) {
        if (active.containsKey(source.id))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancel the current synchronization before changing this source");
        if (source.status == SourceModels.Status.RUNNING) {
            // A failed final DB write may leave RUNNING even after the local worker exited.
            source.status = SourceModels.Status.INTERRUPTED;
            runs.findFirstBySourceIdOrderByStartedAtDesc(source.id).ifPresent(run -> {
                if (run.status == SourceModels.Status.RUNNING) {
                    run.status = SourceModels.Status.INTERRUPTED; run.completedAt = Instant.now();
                    run.error = "Previous synchronization stopped before its completion could be recorded.";
                    runs.saveAndFlush(run);
                }
            });
        }
    }
    private void validate(SourceModels.Request request) {
        if (request.intervalMinutes() > 0 && request.intervalMinutes() < 5) throw new IllegalArgumentException("Schedule must be manual or at least 5 minutes");
        SourceSupport.validateFilters(request.config()); connector(request.type()).validate(request.config());
    }
    private SourceConnector connector(SourceModels.Type type) {
        SourceConnector connector = connectors.get(type);
        if (connector == null) throw new IllegalArgumentException("Unknown connector type");
        return connector;
    }
    private void apply(SourceInstance source, SourceModels.Request request) {
        source.name = request.name().trim(); source.type = request.type(); source.collection = request.collection();
        source.enabled = request.enabled(); source.intervalMinutes = request.intervalMinutes();
        try { source.configJson = json.writeValueAsString(request.config()); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid connector configuration"); }
    }
    private SourceModels.Config config(SourceInstance source) {
        try { return json.readValue(source.configJson, SourceModels.Config.class); }
        catch (Exception e) { throw new IllegalStateException("Stored connector configuration could not be read"); }
    }
    private static Instant next(SourceInstance source) {
        return source.enabled && source.intervalMinutes > 0 ? Instant.now().plusSeconds(source.intervalMinutes * 60L) : null;
    }
    private SourceModels.Source dto(SourceInstance s) {
        return new SourceModels.Source(s.id, s.name, s.type, s.collection, s.enabled, s.intervalMinutes,
                config(s), s.status, documents.countBySourceIdAndIndexedAtIsNotNull(s.id), s.lastSuccessAt,
                s.nextSyncAt, s.lastError, runs.findFirstBySourceIdOrderByStartedAtDesc(s.id).map(SourceRun::toDto).orElse(null));
    }
    @PreDestroy public void stop() { active.values().forEach(flag -> flag.set(true)); executor.shutdownNow(); }
}
