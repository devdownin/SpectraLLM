package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BenchmarkDraftServiceTest {

    @TempDir Path dir;

    private ChromaDbClient chroma;
    private LlmChatClient llm;
    private IngestedFileRepository files;
    private BenchmarkDraftService service;

    private static final String LONG = "x".repeat(250);

    @BeforeEach
    void setUp() {
        chroma = mock(ChromaDbClient.class);
        llm = mock(LlmChatClient.class);
        files = mock(IngestedFileRepository.class);
        SpectraProperties props = mock(SpectraProperties.class);
        when(chroma.getOrCreateCollection("spectra_documents")).thenReturn("col-1");
        // a.pdf : trois passages, b.pdf : un, plus un passage trop court.
        when(chroma.getDocumentsPaged(eq("col-1"), anyInt(), eq(0))).thenReturn(Map.of(
                "documents", List.of("A1 " + LONG, "A2 " + LONG, "A3 " + LONG, "B1 " + LONG, "court"),
                "metadatas", List.of(meta("a.pdf", "sha-a"), meta("a.pdf", "sha-a"), meta("a.pdf", "sha-a"),
                        meta("b.pdf", "sha-b"), meta("c.pdf", "sha-c"))));
        IngestedFileEntity a = new IngestedFileEntity("sha-a", "a.pdf", "pdf", Instant.now(), 3);
        a.setCategories(List.of("juridique"));
        when(files.findAllByOrderByIngestedAtDesc()).thenReturn(List.of(a));
        when(llm.chatJson(anyString(), anyString(), anyFloat(), anyFloat())).thenAnswer(inv -> {
            String passage = inv.getArgument(1);
            String id = passage.substring(passage.indexOf(":\n") + 2, passage.indexOf(":\n") + 4);
            return id.equals("A2") ? "{\"question\": \"\"}"
                    : "```json\n{\"question\": \"Q " + id + " ?\", \"reference\": \"R " + id + "\"}\n```";
        });
        service = new BenchmarkDraftService(chroma, llm, files, props, dir.toString());
    }

    private static Map<String, Object> meta(String source, String sha) {
        return Map.of("sourceFile", source, "sha256", sha);
    }

    @Test
    void draft_alterneLesSources_ecarteLesPassagesInexploitables_etEcritDuJsonl() throws Exception {
        BenchmarkDraftService.DraftResult result = service.draft(null, 3);

        assertThat(result.scannedChunks()).isEqualTo(5);
        assertThat(result.items()).extracting(i -> i.expectedSources().getFirst())
                .containsExactlyInAnyOrder("a.pdf", "a.pdf", "b.pdf");
        assertThat(result.items()).allSatisfy(i -> {
            assertThat(i.answerable()).isTrue();
            assertThat(i.draft()).isTrue();
            assertThat(i.question()).isNotBlank();
        });
        assertThat(result.items()).allSatisfy(i -> assertThat(i.category())
                .isEqualTo(i.expectedSources().getFirst().equals("a.pdf") ? "juridique" : "general"));

        List<String> lines = Files.readAllLines(Path.of(result.file()));
        assertThat(lines).hasSize(3);
        JsonNode first = new ObjectMapper().readTree(lines.getFirst());
        assertThat(first.path("expectedSources").get(0).asText())
                .isEqualTo(result.items().getFirst().expectedSources().getFirst());
        assertThat(Path.of(result.file()).getFileName().toString()).startsWith("draft-").endsWith(".jsonl");
    }

    @Test
    void draft_tourDeRole_unGrosDocumentNeMonopolisePasLeJeu() throws Exception {
        // a.pdf a trois passages, b.pdf un seul : dès le premier tour, b.pdf est servi.
        assertThat(service.draft(null, 2).items()).extracting(i -> i.expectedSources().getFirst())
                .contains("b.pdf");
    }

    @Test
    void draft_tailleBornee_etQuestionVideIgnoree() throws Exception {
        BenchmarkDraftService.DraftResult result = service.draft(null, 500);

        // 4 passages exploitables, dont A2 sans question.
        assertThat(result.items()).hasSize(3);
        assertThat(result.items()).extracting(BenchmarkDraftService.DraftItem::question).doesNotContain("Q A2 ?");
    }

    private BenchmarkDraftService.DraftJob await(String jobId, java.util.function.Predicate<BenchmarkDraftService.DraftJob> done)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            BenchmarkDraftService.DraftJob job = service.getJob(jobId);
            if (done.test(job)) return job;
            Thread.sleep(25);
        }
        throw new AssertionError("job non terminé : " + service.getJob(jobId));
    }

    @Test
    void submit_produitLesQuestionsEnTacheDeFond_puisEcritLeFichier() throws Exception {
        String jobId = service.submit(null, 3);

        BenchmarkDraftService.DraftJob job = await(jobId, j -> j.status() == BenchmarkDraftService.JobStatus.COMPLETED);

        assertThat(job.items()).hasSize(3);
        assertThat(job.processed()).isEqualTo(3);
        assertThat(job.scannedChunks()).isEqualTo(5);
        assertThat(Files.readAllLines(Path.of(job.file()))).hasSize(3);
    }

    @Test
    void submit_refuseUnSecondBrouillonPendantQueLePremierTourne_etPermetDAnnuler() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            release.await();
            return "{\"question\": \"Q ?\", \"reference\": \"R\"}";
        }).when(llm).chatJson(anyString(), anyString(), anyFloat(), anyFloat());

        String jobId = service.submit(null, 4);

        assertThat(jobId).isNotNull();
        assertThat(service.submit(null, 4)).isNull();
        assertThat(service.cancel(jobId)).isTrue();
        release.countDown();
        BenchmarkDraftService.DraftJob job = await(jobId, j -> j.status() == BenchmarkDraftService.JobStatus.CANCELLED);
        // Annulation prise en compte entre deux questions : celle en cours aboutit, les suivantes non.
        assertThat(job.items()).hasSizeLessThanOrEqualTo(1);
        assertThat(service.cancel(jobId)).isFalse();
        assertThat(service.getJob("inconnu")).isNull();
    }

    @Test
    void submit_enEchec_exposeLaCause() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("chroma absent"))
                .when(chroma).getOrCreateCollection("spectra_documents");

        BenchmarkDraftService.DraftJob job = await(service.submit(null, 3),
                j -> j.status() == BenchmarkDraftService.JobStatus.FAILED);

        assertThat(job.error()).contains("chroma absent");
    }
}
