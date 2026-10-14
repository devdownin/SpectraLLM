package fr.spectra.controller;

import fr.spectra.dto.GedDocumentFilter;
import fr.spectra.persistence.*;
import fr.spectra.service.DocumentClassificationService;
import fr.spectra.service.GedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de GedController (sans contexte Spring MVC).
 * GedService est mocké ; on invoque les méthodes du contrôleur directement.
 */
class GedControllerTest {

    private GedService                    gedService;
    private DocumentClassificationService  classificationService;
    private GedController                  controller;

    @BeforeEach
    void setUp() {
        gedService            = mock(GedService.class);
        classificationService = mock(DocumentClassificationService.class);
        controller            = new GedController(gedService, classificationService);
    }

    // ── listAll — filtrage paginé (amélioration 2) ────────────────────────────

    @Test
    void listAll_noFilter_returnsPagedResult() {
        Page<IngestedFileEntity> page = new PageImpl<>(
                List.of(entity("sha1"), entity("sha2")), PageRequest.of(0, 20), 2);
        when(gedService.findFiltered(any())).thenReturn(page);

        Map<String, Object> result = controller.listAll(null, null, null, null, null, null, null, null, null, 0, 20);

        assertThat((List<?>) result.get("content")).hasSize(2);
        assertThat(result).containsKey("totalElements");
    }

    @Test
    void listAll_withLifecycleFilter_passesLifecycleToService() {
        Page<IngestedFileEntity> page = new PageImpl<>(List.of(entity("sha1")));
        when(gedService.findFiltered(any())).thenReturn(page);

        controller.listAll("QUALIFIED", null, null, null, null, null, null, null, null, 0, 20);

        verify(gedService).findFiltered(argThat(f ->
                f.lifecycle() == IngestedFileEntity.Lifecycle.QUALIFIED));
    }

    @Test
    void listAll_withTagFilter_passesTagToService() {
        Page<IngestedFileEntity> page = new PageImpl<>(List.of());
        when(gedService.findFiltered(any())).thenReturn(page);

        controller.listAll(null, "kafka", null, null, null, null, null, null, null, 0, 20);

        verify(gedService).findFiltered(argThat(f -> "kafka".equals(f.tag())));
    }

    @Test
    void listAll_invalidLifecycle_throwsIllegalArgumentException() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.listAll("BROKEN", null, null, null, null, null, null, null, null, 0, 20));
    }

    @Test
    void listAll_invalidFromDate_throwsIllegalArgumentException() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.listAll(null, null, null, null, "22-04-2026", null, null, null, null, 0, 20));
    }

    @Test
    void listAll_sheetContainsExpectedKeys() {
        Page<IngestedFileEntity> page = new PageImpl<>(List.of(entity("sha1")));
        when(gedService.findFiltered(any())).thenReturn(page);

        Map<String, Object> result = controller.listAll(null, null, null, null, null, null, null, null, null, 0, 20);
        @SuppressWarnings("unchecked")
        Map<String, Object> sheet = ((List<Map<String, Object>>) result.get("content")).get(0);

        assertThat(sheet).containsKeys("sha256", "fileName", "lifecycle", "version",
                "tags", "qualityScore", "chunksCreated", "collectionName");
    }

    @Test
    void listAll_paginationMetadataPresent() {
        Page<IngestedFileEntity> page = new PageImpl<>(
                List.of(entity("sha1")), PageRequest.of(0, 5), 42);
        when(gedService.findFiltered(any())).thenReturn(page);

        Map<String, Object> result = controller.listAll(null, null, null, null, null, null, null, null, null, 0, 5);

        assertThat(result.get("totalElements")).isEqualTo(42L);
        assertThat(result.get("totalPages")).isEqualTo(9);
    }

    // ── getDocument ───────────────────────────────────────────────────────────

    @Test
    void getDocument_found_returns200WithSheet() {
        when(gedService.findById("sha1")).thenReturn(Optional.of(entity("sha1")));
        when(gedService.getLinksForDocument("sha1")).thenReturn(List.of());
        when(gedService.getAuditTrail("sha1")).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> resp = controller.getDocument("sha1");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsKeys("sha256", "modelLinks", "auditTrail");
    }

    @Test
    void getDocument_notFound_returns404() {
        when(gedService.findById("missing")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.getDocument("missing");

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // ── Amélioration 3 — deleteDocument ──────────────────────────────────────

    @Test
    void deleteDocument_found_returns200WithSummary() {
        when(gedService.deleteDocument("sha1", "api"))
                .thenReturn(Map.of("sha256", "sha1", "chunksDeleted", 5,
                        "fileName", "doc.pdf", "actor", "api"));

        ResponseEntity<Map<String, Object>> resp = controller.deleteDocument("sha1", "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsKey("chunksDeleted");
    }

    @Test
    void deleteDocument_notFound_returns404() {
        when(gedService.deleteDocument(eq("missing"), anyString()))
                .thenThrow(new NoSuchElementException());

        ResponseEntity<Map<String, Object>> resp = controller.deleteDocument("missing", "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // ── setLifecycle — machine à états ────────────────────────────────────────

    @Test
    void setLifecycle_validTransition_returns200() {
        IngestedFileEntity doc = entity("sha1");
        doc.setLifecycle(IngestedFileEntity.Lifecycle.QUALIFIED);
        when(gedService.transitionLifecycle("sha1", IngestedFileEntity.Lifecycle.QUALIFIED, "api"))
                .thenReturn(doc);

        ResponseEntity<Map<String, Object>> resp =
                controller.setLifecycle("sha1", "QUALIFIED", "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("lifecycle", "QUALIFIED");
    }

    @Test
    void setLifecycle_invalidEnumValue_returns400() {
        ResponseEntity<Map<String, Object>> resp =
                controller.setLifecycle("sha1", "UNKNOWN_STATUS", "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).containsKey("error");
    }

    @Test
    void setLifecycle_forbiddenTransition_returns400WithMessage() {
        when(gedService.transitionLifecycle(eq("sha1"), any(), anyString()))
                .thenThrow(new IllegalStateException("Transition interdite : INGESTED → TRAINED"));

        ResponseEntity<Map<String, Object>> resp =
                controller.setLifecycle("sha1", "TRAINED", "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("error").toString()).contains("Transition interdite");
    }

    @Test
    void setLifecycle_documentNotFound_returns404() {
        when(gedService.transitionLifecycle(eq("missing"), any(), anyString()))
                .thenThrow(new NoSuchElementException());

        ResponseEntity<Map<String, Object>> resp =
                controller.setLifecycle("missing", "QUALIFIED", "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // ── addTags ───────────────────────────────────────────────────────────────

    @Test
    void addTags_returnsUpdatedTagList() {
        IngestedFileEntity doc = entity("sha1");
        doc.setTags(List.of("kafka", "xml"));
        when(gedService.addTags("sha1", List.of("kafka", "xml"), "api")).thenReturn(doc);

        ResponseEntity<Map<String, Object>> resp =
                controller.addTags("sha1", List.of("kafka", "xml"), "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsKey("tags");
    }

    @Test
    void addTags_documentNotFound_returns404() {
        when(gedService.addTags(eq("missing"), any(), anyString()))
                .thenThrow(new NoSuchElementException());

        ResponseEntity<Map<String, Object>> resp =
                controller.addTags("missing", List.of("tag"), "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // ── removeTags ────────────────────────────────────────────────────────────

    @Test
    void removeTags_returnsUpdatedTagList() {
        IngestedFileEntity doc = entity("sha1");
        doc.setTags(List.of("kafka"));
        when(gedService.removeTags("sha1", List.of("xml"), "api")).thenReturn(doc);

        ResponseEntity<Map<String, Object>> resp =
                controller.removeTags("sha1", List.of("xml"), "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    // ── linkToModel ───────────────────────────────────────────────────────────

    @Test
    void linkToModel_validRequest_returns200() {
        DocumentModelLinkEntity link = new DocumentModelLinkEntity(
                "sha1", "phi-mini", DocumentModelLinkEntity.LinkType.TRAINED_ON, Instant.now());
        when(gedService.linkToModel("sha1", "phi-mini",
                DocumentModelLinkEntity.LinkType.TRAINED_ON, "api")).thenReturn(link);

        ResponseEntity<Map<String, Object>> resp =
                controller.linkToModel("sha1", "phi-mini", "TRAINED_ON", "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("modelName", "phi-mini");
    }

    @Test
    void linkToModel_invalidLinkType_returns400() {
        ResponseEntity<Map<String, Object>> resp =
                controller.linkToModel("sha1", "model", "INVALID_TYPE", "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    // ── getDocumentsByModel ───────────────────────────────────────────────────

    @Test
    void getDocumentsByModel_returnsDocumentList() {
        when(gedService.getDocumentsByModel("phi-mini")).thenReturn(List.of(entity("sha1")));

        List<Map<String, Object>> result = controller.getDocumentsByModel("phi-mini");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("sha256", "sha1");
    }

    // ── getAudit ──────────────────────────────────────────────────────────────

    @Test
    void getAudit_documentExists_returnsTrail() {
        AuditLogEntity entry = new AuditLogEntity(
                "sha1", AuditLogEntity.Action.INGESTED, "system", Instant.now(), null);
        when(gedService.findById("sha1")).thenReturn(Optional.of(entity("sha1")));
        when(gedService.getAuditTrail("sha1")).thenReturn(List.of(entry));

        ResponseEntity<List<Map<String, Object>>> resp = controller.getAudit("sha1");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(1);
    }

    @Test
    void getAudit_documentNotFound_returns404() {
        when(gedService.findById("missing")).thenReturn(Optional.empty());

        assertThat(controller.getAudit("missing").getStatusCode().value()).isEqualTo(404);
    }

    // ── Amélioration 5 — stats ────────────────────────────────────────────────

    @Test
    void stats_delegatesToService() {
        when(gedService.stats()).thenReturn(Map.of(
                "total", 10L,
                "byLifecycle", Map.of("INGESTED", 7L, "QUALIFIED", 3L),
                "avgQualityScore", 0.72,
                "topTags", List.of(),
                "totalChunks", 150L,
                "qualityDistribution", Map.of()));

        Map<String, Object> result = controller.stats();

        assertThat(result).containsKey("total");
        assertThat(result.get("total")).isEqualTo(10L);
        assertThat(result).containsKeys("byLifecycle", "avgQualityScore", "totalChunks");
    }

    // ── bulkLifecycle ─────────────────────────────────────────────────────────

    @Test
    void bulkLifecycle_allSucceed_reportsCorrectCount() {
        IngestedFileEntity doc = entity("sha1");
        doc.setLifecycle(IngestedFileEntity.Lifecycle.QUALIFIED);
        when(gedService.transitionLifecycle(anyString(),
                eq(IngestedFileEntity.Lifecycle.QUALIFIED), anyString())).thenReturn(doc);

        Map<String, Object> result =
                controller.bulkLifecycle(List.of("sha1", "sha2"), "QUALIFIED", "api");

        assertThat(result).containsEntry("updated", 2);
        assertThat((List<?>) result.get("errors")).isEmpty();
    }

    @Test
    void bulkLifecycle_forbiddenTransition_recordsError() {
        when(gedService.transitionLifecycle(anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("Transition interdite"));

        Map<String, Object> result =
                controller.bulkLifecycle(List.of("sha1"), "TRAINED", "api");

        assertThat(result).containsEntry("updated", 0);
        assertThat((List<?>) result.get("errors")).hasSize(1);
    }

    @Test
    void bulkLifecycle_invalidLifecycle_throwsIllegalArgumentException() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.bulkLifecycle(List.of("sha1"), "BROKEN", "api"));
    }

    // ── R8 — Classification automatique ──────────────────────────────────────

    @Test
    void classify_success_returns200WithCategories() {
        when(classificationService.classify("sha1", "api", false)).thenReturn(
                new DocumentClassificationService.ClassificationResult(
                        "sha1", "doc.pdf", List.of("procedures"), Map.of("procedures", 0.9),
                        "Une procédure.", "phi-4-mini", Instant.now(), false));

        ResponseEntity<Map<String, Object>> resp = controller.classify("sha1", false, "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("categories", List.of("procedures"))
                                  .containsEntry("model", "phi-4-mini")
                                  .containsEntry("reused", false);
    }

    @Test
    void classify_unknownDocument_returns404() {
        when(classificationService.classify(eq("missing"), anyString(), anyBoolean()))
                .thenThrow(new NoSuchElementException());

        assertThat(controller.classify("missing", false, "api").getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void classify_llmUnusable_returns422NotServerError() {
        // La requête est recevable ; c'est son traitement qui n'aboutit pas.
        when(classificationService.classify(eq("sha1"), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("Aucun contenu indexé pour sha1"));

        ResponseEntity<Map<String, Object>> resp = controller.classify("sha1", false, "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody().get("error").toString()).contains("Aucun contenu indexé");
    }

    @Test
    void bulkClassify_accepted_returns202WithTaskId() {
        when(classificationService.submitBatch(any(), anyString(), anyBoolean()))
                .thenReturn("task-1");

        ResponseEntity<Map<String, Object>> resp =
                controller.bulkClassify(List.of("sha1"), false, "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        assertThat(resp.getBody()).containsEntry("taskId", "task-1");
    }

    @Test
    void bulkClassify_batchAlreadyRunning_returns409() {
        when(classificationService.submitBatch(any(), anyString(), anyBoolean())).thenReturn(null);

        ResponseEntity<Map<String, Object>> resp = controller.bulkClassify(null, false, "api");

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void bulkClassify_serviceDisabled_returns422() {
        when(classificationService.submitBatch(any(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("La classification automatique est désactivée"));

        assertThat(controller.bulkClassify(null, false, "api").getStatusCode().value())
                .isEqualTo(422);
    }

    @Test
    void classificationTask_unknownId_returns404() {
        when(classificationService.getBatchTask("nope")).thenReturn(null);

        assertThat(controller.classificationTask("nope").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void classificationTask_known_returnsProgressPayload() {
        when(classificationService.getBatchTask("task-1")).thenReturn(
                new DocumentClassificationService.BatchTask("task-1",
                        DocumentClassificationService.BatchTask.Status.PROCESSING,
                        3, 10, 2, 1, List.of("shaX : LLM muet"), Instant.now()));

        ResponseEntity<Map<String, Object>> resp = controller.classificationTask("task-1");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("status", "PROCESSING")
                                  .containsEntry("processed", 3)
                                  .containsEntry("total", 10)
                                  .containsEntry("failed", 1);
    }

    @Test
    void cancelClassification_terminatedTask_returns400() {
        when(classificationService.cancelBatch("done")).thenReturn(false);

        assertThat(controller.cancelClassification("done").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void listAll_passesCategoryAndClassifiedFiltersToService() {
        when(gedService.findFiltered(any())).thenReturn(new PageImpl<>(List.of()));

        controller.listAll(null, null, null, null, null, null, null, "securite", false, 0, 20);

        verify(gedService).findFiltered(argThat(f ->
                "securite".equals(f.category()) && Boolean.FALSE.equals(f.classified())));
    }

    @Test
    void sheet_exposesClassificationFields() {
        IngestedFileEntity doc = entity("sha1");
        doc.setCategories(List.of("procedures"));
        doc.setCategoryScores("{\"procedures\":0.9}");
        doc.setClassificationSummary("Une procédure.");
        doc.setClassifierModel("phi-4-mini");
        doc.setClassifiedAt(Instant.now());
        when(gedService.findById("sha1")).thenReturn(Optional.of(doc));
        when(gedService.getLinksForDocument("sha1")).thenReturn(List.of());
        when(gedService.getAuditTrail("sha1")).thenReturn(List.of());

        Map<String, Object> sheet = controller.getDocument("sha1").getBody();

        assertThat(sheet).containsEntry("categories", List.of("procedures"))
                         .containsEntry("categoryScores", Map.of("procedures", 0.9))
                         .containsEntry("classificationSummary", "Une procédure.")
                         .containsEntry("classifierModel", "phi-4-mini");
        assertThat(sheet.get("classifiedAt")).isNotNull();
    }

    @Test
    void bulkAddTags_allSucceed_reportsCount() {
        IngestedFileEntity doc = entity("sha1");
        when(gedService.addTags(anyString(), any(), anyString())).thenReturn(doc);

        GedController.BulkTagRequest req = new GedController.BulkTagRequest(
                List.of("sha1", "sha2"), List.of("kafka"));
        Map<String, Object> result = controller.bulkAddTags(req, "api");

        assertThat(result).containsEntry("updated", 2);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static IngestedFileEntity entity(String sha256) {
        return new IngestedFileEntity(sha256, "doc.pdf", "PDF",
                Instant.now(), 5, "spectra_documents", 0.6);
    }
}
