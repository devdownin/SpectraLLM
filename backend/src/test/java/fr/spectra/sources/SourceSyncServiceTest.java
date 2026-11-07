package fr.spectra.sources;

import fr.spectra.service.IngestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceSyncServiceTest {
    private final SourceInstanceRepository sources = mock(SourceInstanceRepository.class);
    private final SourceDocumentRepository documents = mock(SourceDocumentRepository.class);
    private final SourceRunRepository runs = mock(SourceRunRepository.class);
    private final org.springframework.transaction.PlatformTransactionManager transactionManager =
            mock(org.springframework.transaction.PlatformTransactionManager.class);
    private final SourceIndexJobRepository jobs = mock(SourceIndexJobRepository.class);
    private final Map<String, SourceIndexJob> jobStore = new java.util.concurrent.ConcurrentHashMap<>();
    private final IngestionService ingestion = mock(IngestionService.class);
    private final Map<String, SourceInstance> sourceStore = new ConcurrentHashMap<>();
    private final Map<String, SourceDocument> docStore = new ConcurrentHashMap<>();
    private final Map<String, SourceRun> runStore = new ConcurrentHashMap<>();
    private final FakeConnector connector = new FakeConnector();
    private SourceSyncService service;

    @BeforeEach void setup() throws Exception {
        when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new org.springframework.transaction.support.SimpleTransactionStatus());
        when(sources.findById(anyString())).thenAnswer(a -> Optional.ofNullable(sourceStore.get(a.getArgument(0))));
        when(sources.saveAndFlush(any())).thenAnswer(a -> { SourceInstance s = a.getArgument(0); sourceStore.put(s.id, s); return s; });
        when(sources.findAllByOrderByNameAsc()).thenAnswer(a -> new ArrayList<>(sourceStore.values()));
        doAnswer(a -> { sourceStore.remove(a.getArgument(0)); return null; }).when(sources).deleteById(anyString());
        when(documents.findById(anyString())).thenAnswer(a -> Optional.ofNullable(docStore.get(a.getArgument(0))));
        when(documents.saveAndFlush(any())).thenAnswer(a -> { SourceDocument d = a.getArgument(0); docStore.put(d.id, d); return d; });
        doAnswer(a -> { docStore.remove(a.getArgument(0)); return null; }).when(documents).deleteById(anyString());
        when(documents.countBySourceIdAndIndexedAtIsNotNull(anyString())).thenAnswer(a -> docStore.values().stream().filter(d -> d.sourceId.equals(a.getArgument(0)) && d.indexedAt != null).count());
        when(documents.findBySourceId(anyString())).thenAnswer(a -> docStore.values().stream().filter(d -> d.sourceId.equals(a.getArgument(0))).toList());
        when(documents.findBySourceIdAndSeenRunNot(anyString(), anyString(), any(Pageable.class))).thenAnswer(a -> new PageImpl<>(docStore.values().stream().filter(d -> d.sourceId.equals(a.getArgument(0)) && !Objects.equals(d.seenRun, a.getArgument(1))).toList()));
        when(runs.saveAndFlush(any())).thenAnswer(a -> { SourceRun r = a.getArgument(0); runStore.put(r.id, r); return r; });
        when(runs.findByStatus(any())).thenAnswer(a -> runStore.values().stream().filter(r -> r.status == a.getArgument(0)).toList());
        when(runs.findFirstBySourceIdOrderByStartedAtDesc(anyString())).thenAnswer(a -> runStore.values().stream().filter(r -> r.sourceId.equals(a.getArgument(0))).max(Comparator.comparing(r -> r.startedAt)));
        when(runs.findAll()).thenAnswer(a -> new ArrayList<>(runStore.values()));
        when(ingestion.upsertFromStream(anyString(), anyString(), any(InputStream.class), anyString(), isNull(), anyMap())).thenAnswer(a -> new IngestionService.UpsertResult(a.getArgument(0), IngestionService.UpsertResult.Kind.UPSERTED, 0, 1));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> {
            SourceIndexJob job = invocation.getArgument(0);
            jobStore.put(job.id, job);
            return job;
        });
        when(jobs.findFirstBySourceIdAndRunIdOrderById(anyString(), anyString())).thenAnswer(invocation ->
                jobStore.values().stream().filter(job -> job.sourceId.equals(invocation.getArgument(0))
                        && job.runId.equals(invocation.getArgument(1))).findFirst());
        doAnswer(invocation -> {
            jobStore.remove(invocation.getArgument(0));
            return null;
        }).when(jobs).deleteById(anyString());
        doAnswer(invocation -> {
            jobStore.values().removeIf(job -> job.sourceId.equals(invocation.getArgument(0)));
            return null;
        }).when(jobs).clearSource(anyString());
        service = new SourceSyncService(sources, documents, runs, jobs, ingestion,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), transactionManager, List.of(connector), 1);
    }
    @AfterEach void stop() { service.stop(); }

    @Test void unchangedDocumentsResumeAfterServiceRestartWithoutEmbeddingAgain() throws Exception {
        String id = create("Knowledge");
        connector.items = List.of(item("guide.txt", "first"));
        run(id, SourceModels.Status.SUCCESS);
        SourceDocument checkpoint = docStore.values().iterator().next();
        Instant indexedAt = checkpoint.indexedAt;
        service.stop();
        service = new SourceSyncService(sources, documents, runs, jobs, ingestion,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), transactionManager, List.of(connector), 1);
        service.recover();
        SourceModels.Run second = run(id, SourceModels.Status.SUCCESS);
        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(second.indexed()).isZero();
        assertThat(checkpoint.indexedAt).isEqualTo(indexedAt);
        verify(ingestion, times(1)).upsertFromStream(anyString(), anyString(), any(), anyString(), isNull(), anyMap());
    }

    @Test void failedReplacementKeepsPublishedCheckpointAndNeverPurgesMissingDocuments() throws Exception {
        String id = create("Knowledge");
        connector.items = List.of(item("guide.txt", "first"), item("keep.txt", "kept"));
        run(id, SourceModels.Status.SUCCESS);
        SourceDocument original = docStore.values().stream().filter(d -> d.externalId.equals("guide.txt")).findFirst().orElseThrow();
        String revision = original.revision;
        Instant indexedAt = original.indexedAt;
        doThrow(new IllegalStateException("https://signed.invalid/?secret=must-not-leak"))
                .when(ingestion).upsertFromStream(eq(original.sourceKey()), anyString(), any(), anyString(), isNull(), anyMap());
        connector.items = List.of(item("guide.txt", "changed"));
        SourceModels.Run failed = run(id, SourceModels.Status.PARTIAL);
        assertThat(failed.failed()).isEqualTo(1);
        assertThat(original.revision).isEqualTo(revision);
        assertThat(original.indexedAt).isEqualTo(indexedAt);
        assertThat(original.error).doesNotContain("secret", "signed.invalid");
        assertThat(docStore).hasSize(2);
        verify(ingestion, never()).purgeStreamSource(anyString(), anyString());
    }

    @Test void incompleteListingKeepsPreviouslyIndexedDocumentsAbsentFromThatListing() throws Exception {
        String id = create("Knowledge");
        connector.items = List.of(item("old.txt", "old"));
        run(id, SourceModels.Status.SUCCESS);
        connector.items = List.of(item("new.txt", "new")); connector.complete = false;
        SourceModels.Run partial = run(id, SourceModels.Status.PARTIAL);
        assertThat(partial.error()).contains("limit");
        assertThat(docStore).hasSize(2);
        verify(ingestion, never()).purgeStreamSource(anyString(), anyString());
    }

    @Test void completeEmptyListingRemovesDocumentsFromBothIndexesThroughSharedPurge() throws Exception {
        String id = create("Knowledge");
        connector.items = List.of(item("gone.txt", "gone"));
        run(id, SourceModels.Status.SUCCESS);
        SourceDocument original = docStore.values().iterator().next();
        connector.items = List.of();
        SourceModels.Run removed = run(id, SourceModels.Status.SUCCESS);
        assertThat(removed.deleted()).isEqualTo(1);
        assertThat(docStore).isEmpty();
        verify(ingestion).purgeStreamSource(original.sourceKey(), "spectra_documents");
    }

    @Test void sourceInstancesNeverShareDocumentIdentityEvenWithSameExternalId() throws Exception {
        String first = create("First"); String second = create("Second");
        connector.items = List.of(item("same.txt", "same"));
        run(first, SourceModels.Status.SUCCESS); run(second, SourceModels.Status.SUCCESS);
        assertThat(docStore).hasSize(2);
        assertThat(docStore.values().stream().map(d -> d.sourceId)).containsExactlyInAnyOrder(first, second);
        assertThat(docStore.values().stream().map(SourceDocument::sourceKey)).doesNotHaveDuplicates();
    }

    @Test void changedContentReplacesOneLogicalDocumentWithoutCreatingDuplicateCheckpoint() throws Exception {
        String id = create("Knowledge"); connector.items = List.of(item("guide.txt", "first"));
        run(id, SourceModels.Status.SUCCESS);
        connector.items = List.of(item("guide.txt", "second"));
        SourceModels.Run changed = run(id, SourceModels.Status.SUCCESS);
        assertThat(changed.indexed()).isEqualTo(1);
        assertThat(docStore).hasSize(1);
        assertThat(docStore.values().iterator().next().revision).isEqualTo("second");
        verify(ingestion, times(2)).upsertFromStream(anyString(), anyString(), any(), anyString(), isNull(), anyMap());
    }

    @Test void runningSourceCannotStartTwiceOrBeEditedAndCancellationRetainsPublishedData() throws Exception {
        String id = create("Knowledge");
        CountDownLatch entered = new CountDownLatch(1);
        connector.block = entered;
        service.sync(id);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> service.sync(id)).isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> service.update(id, request("Changed"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ResponseStatusException.class);
        service.cancel(id);
        await().atMost(Duration.ofSeconds(3)).until(() -> service.get(id).status() == SourceModels.Status.CANCELLED);
        verify(ingestion, never()).purgeStreamSource(anyString(), anyString());
    }

    @Test void concurrencyLimitRejectsOtherSourceUntilCurrentRunFinishes() throws Exception {
        String first = create("First"); String second = create("Second");
        CountDownLatch entered = new CountDownLatch(1); connector.block = entered;
        service.sync(first); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> service.sync(second)).isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(429));
        service.cancel(first);
        await().atMost(Duration.ofSeconds(3)).until(() -> service.get(first).status() == SourceModels.Status.CANCELLED);
    }

    @Test void recoveryMarksAbandonedRunInterruptedAndPreservesDocuments() throws Exception {
        String id = create("Knowledge");
        SourceInstance source = sourceStore.get(id); source.status = SourceModels.Status.RUNNING;
        SourceRun run = new SourceRun(); run.id = "interrupted"; run.sourceId = id; run.startedAt = Instant.now(); run.status = SourceModels.Status.RUNNING;
        runStore.put(run.id, run);
        SourceDocument document = new SourceDocument(); document.id = "preserved"; document.sourceId = id; document.fingerprint = "checkpoint"; docStore.put(document.id, document);
        service.recover();
        assertThat(service.get(id).status()).isEqualTo(SourceModels.Status.INTERRUPTED);
        assertThat(run.completedAt).isNotNull();
        assertThat(docStore.get("preserved").fingerprint).isEqualTo("checkpoint");
        verifyNoInteractions(ingestion);
    }


    @Test void durableIdentityExistsBeforePublicationSoInterruptedItemsCanBeReconciled() throws Exception {
        String sourceId = create("Knowledge");
        String externalId = "new-guide.txt";
        var checkpointAtPublication = new java.util.concurrent.atomic.AtomicReference<Optional<SourceModels.Document>>(Optional.empty());
        when(ingestion.upsertFromStream(anyString(), anyString(), any(InputStream.class), anyString(), isNull(), anyMap()))
                .thenAnswer(a -> {
                    checkpointAtPublication.set(Optional.ofNullable(docStore.get(SourceSupport.hash(sourceId + "\n" + externalId))).map(SourceDocument::toDto));
                    return new IngestionService.UpsertResult(a.getArgument(0), IngestionService.UpsertResult.Kind.UPSERTED, 0, 1);
                });
        connector.items = List.of(item(externalId, "searchable content"));
        run(sourceId, SourceModels.Status.SUCCESS);
        assertThat(checkpointAtPublication.get()).isPresent();
        SourceModels.Document checkpoint = checkpointAtPublication.get().orElseThrow();
        assertThat(checkpoint.externalId()).isEqualTo(externalId);
        assertThat(checkpoint.indexedAt()).isNull();
        assertThat(checkpoint.lastSeenAt()).isNotNull();
        assertThat(docStore.values()).hasSize(1);
    }


    @Test void restartCanRemovePublicationWhoseWorkerDiedBeforeSuccessfulCheckpointCompletion() throws Exception {
        String sourceId = create("Knowledge");
        var publicationExists = new java.util.concurrent.atomic.AtomicBoolean();
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        when(ingestion.upsertFromStream(anyString(), anyString(), any(InputStream.class), anyString(), isNull(), anyMap()))
                .thenAnswer(a -> {
                    worker.set(Thread.currentThread());
                    publicationExists.set(true);
                    throw new SimulatedProcessFailure();
                });
        doAnswer(a -> { publicationExists.set(false); return 1; }).when(ingestion).purgeStreamSource(anyString(), anyString());
        connector.items = List.of(item("disappeared.txt", "published before interruption"));
        service.sync(sourceId);
        await().atMost(Duration.ofSeconds(5)).until(() -> worker.get() != null && !worker.get().isAlive());
        assertThat(publicationExists).isTrue();
        service.stop();
        // The source item disappeared before the next scan; only its durable pending identity can find the orphan.
        connector.items = List.of();
        service = new SourceSyncService(sources, documents, runs, jobs, ingestion,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), transactionManager, List.of(connector), 1);
        service.recover();
        SourceModels.Run resumed = run(sourceId, SourceModels.Status.SUCCESS);
        assertThat(resumed.deleted()).isEqualTo(1);
        assertThat(publicationExists).isFalse();
        assertThat(docStore).isEmpty();
    }

    private static class SimulatedProcessFailure extends Error {}


    @Test void pauseAndResumeKeepPublishedDocumentsAndRecomputeSchedule() throws Exception {
        String id = create("Knowledge"); connector.items = List.of(item("guide.txt", "kept"));
        run(id, SourceModels.Status.SUCCESS);
        String fingerprint = docStore.values().iterator().next().fingerprint;
        var paused = service.update(id, new SourceModels.Request("Paused knowledge", SourceModels.Type.LOCAL_FOLDER,
                "spectra_documents", false, 60, config()));
        assertThat(paused.enabled()).isFalse(); assertThat(paused.nextSyncAt()).isNull();
        assertThat(paused.documentCount()).isEqualTo(1);
        assertThatThrownBy(() -> service.sync(id)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
        var resumed = service.update(id, request("Renamed knowledge"));
        assertThat(resumed.name()).isEqualTo("Renamed knowledge");
        assertThat(resumed.enabled()).isTrue(); assertThat(resumed.nextSyncAt()).isAfter(Instant.now());
        assertThat(docStore.values().iterator().next().fingerprint).isEqualTo(fingerprint);
        verify(ingestion, never()).purgeStreamSource(anyString(), anyString());
    }

    @Test void schedulerStartsOnlyDueEnabledSourcesAndLeavesManualAndPausedSourcesAlone() {
        String due = create("Due"); String future = create("Future");
        String manual = service.create(new SourceModels.Request("Manual", SourceModels.Type.LOCAL_FOLDER,
                "spectra_documents", true, 0, config())).id();
        String paused = service.create(new SourceModels.Request("Paused", SourceModels.Type.LOCAL_FOLDER,
                "spectra_documents", false, 60, config())).id();
        sourceStore.get(due).nextSyncAt = Instant.now().minusSeconds(1);
        sourceStore.get(manual).nextSyncAt = Instant.now().minusSeconds(1);
        sourceStore.get(paused).nextSyncAt = Instant.now().minusSeconds(1);
        service.scheduled();
        await().atMost(Duration.ofSeconds(5)).until(() -> service.get(due).status() == SourceModels.Status.SUCCESS);
        assertThat(service.get(future).status()).isEqualTo(SourceModels.Status.IDLE);
        assertThat(service.get(manual).status()).isEqualTo(SourceModels.Status.IDLE);
        assertThat(service.get(paused).status()).isEqualTo(SourceModels.Status.IDLE);
        assertThat(runStore.values()).extracting(r -> r.sourceId).containsExactly(due);
    }

    @Test void immutableCollectionAndTooFrequentScheduleAreRejectedWithoutChangingSource() {
        String id = create("Knowledge");
        assertThatThrownBy(() -> service.update(id, new SourceModels.Request("Moved", SourceModels.Type.LOCAL_FOLDER,
                "another_collection", true, 60, config()))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot change");
        assertThatThrownBy(() -> service.update(id, new SourceModels.Request("Too frequent", SourceModels.Type.LOCAL_FOLDER,
                "spectra_documents", true, 1, config()))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 5");
        assertThat(service.get(id).name()).isEqualTo("Knowledge");
        assertThat(service.get(id).collection()).isEqualTo("spectra_documents");
        assertThat(service.get(id).intervalMinutes()).isEqualTo(60);
    }

    @Test void failedConnectionTestReturnsFailureWithoutPersistingVendorSecretsOrStartingSync() {
        String id = create("Knowledge");
        connector.testFailure = new IllegalStateException("signed-url?secret=must-not-leak");
        var result = service.test(id);
        assertThat(result.success()).isFalse(); assertThat(result.sampleCount()).isZero();
        assertThat(result.message()).contains("IllegalStateException").doesNotContain("secret", "must-not-leak");
        assertThat(service.get(id).status()).isEqualTo(SourceModels.Status.IDLE);
        assertThat(runStore).isEmpty(); verifyNoInteractions(ingestion);
    }

    @Test void failedSourceCleanupDisablesSchedulingAndKeepsEnoughStateForRetry() throws Exception {
        String id = create("Knowledge"); connector.items = List.of(item("guide.txt", "published"));
        run(id, SourceModels.Status.SUCCESS);
        doThrow(new IllegalStateException("temporary index outage")).doReturn(1)
                .when(ingestion).purgeStreamSource(anyString(), anyString());
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(502));
        var failed = service.get(id);
        assertThat(failed.enabled()).isFalse(); assertThat(failed.nextSyncAt()).isNull();
        assertThat(failed.status()).isEqualTo(SourceModels.Status.FAILED);
        assertThat(failed.lastError()).contains("Retry deletion"); assertThat(docStore).hasSize(1);
        service.delete(id);
        assertThat(sourceStore).doesNotContainKey(id); assertThat(docStore).isEmpty();
    }

    @Test void staleRunningStateWithNoWorkerBecomesInterruptedInsteadOfRemainingLocked() {
        String id = create("Knowledge");
        SourceInstance source = sourceStore.get(id); source.status = SourceModels.Status.RUNNING; source.nextSyncAt = null;
        SourceRun orphan = new SourceRun(); orphan.id = "stale-run"; orphan.sourceId = id;
        orphan.startedAt = Instant.now().minusSeconds(60); orphan.status = SourceModels.Status.RUNNING; runStore.put(orphan.id, orphan);
        service.scheduled();
        assertThat(service.get(id).status()).isEqualTo(SourceModels.Status.INTERRUPTED);
        assertThat(service.get(id).nextSyncAt()).isAfter(Instant.now());
        assertThat(orphan.status).isEqualTo(SourceModels.Status.INTERRUPTED); assertThat(orphan.completedAt).isNotNull();
        assertThat(runStore).hasSize(1); verifyNoInteractions(ingestion);
    }

    private String create(String name) { return service.create(request(name)).id(); }
    private static SourceModels.Request request(String name) { return new SourceModels.Request(name, SourceModels.Type.LOCAL_FOLDER, "spectra_documents", true, 60, config()); }
    static SourceModels.Config config() { return new SourceModels.Config("/sources", null, null, null, null, null, null, null, null, null, List.of(), List.of(), 100, 2, 1); }
    private SourceModels.Run run(String id, SourceModels.Status expected) {
        String runId = service.sync(id).id();
        await().atMost(Duration.ofSeconds(5)).until(() -> service.get(id).lastRun() != null && service.get(id).lastRun().id().equals(runId) && service.get(id).status() == expected);
        return service.get(id).lastRun();
    }
    private static SourceConnector.Item item(String external, String content) { return new SourceConnector.Item(external, external, null, external, content, content.getBytes(StandardCharsets.UTF_8), Map.of()); }
    private static class FakeConnector implements SourceConnector {
        volatile List<Item> items = List.of();
        volatile boolean complete = true;
        volatile CountDownLatch block;
        volatile Exception testFailure;
        @Override public SourceModels.Type type() { return SourceModels.Type.LOCAL_FOLDER; }
        @Override public void validate(SourceModels.Config config) {}
        @Override public SourceModels.ConnectionTest test(SourceModels.Config config) throws Exception { if (testFailure != null) throw testFailure; return new SourceModels.ConnectionTest(true, "ok", 0); }
        @Override public boolean scan(SourceModels.Config config, Sink sink) throws Exception {
            if (block != null) { block.countDown(); while (true) { sink.checkCancelled(); Thread.sleep(5); } }
            for (Item item : items) sink.accept(item);
            return complete;
        }
    }
}
