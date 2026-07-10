package fr.spectra.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires de IngestedFileEntity — champs GED (R2, R4, R5, R7).
 */
class IngestedFileEntityTest {

    // ── Constructeur complet ──────────────────────────────────────────────────

    @Test
    void constructor_full_setsAllFields() {
        IngestedFileEntity e = new IngestedFileEntity(
                "sha1", "doc.pdf", "PDF", Instant.EPOCH, 10, "my-collection", 0.85);

        assertThat(e.getSha256()).isEqualTo("sha1");
        assertThat(e.getFileName()).isEqualTo("doc.pdf");
        assertThat(e.getFormat()).isEqualTo("PDF");
        assertThat(e.getChunksCreated()).isEqualTo(10);
        assertThat(e.getCollectionName()).isEqualTo("my-collection");
        assertThat(e.getQualityScore()).isEqualTo(0.85);
    }

    @Test
    void constructor_full_defaultsToIngestedLifecycle() {
        IngestedFileEntity e = new IngestedFileEntity(
                "sha1", "doc.pdf", "PDF", Instant.EPOCH, 5, "coll", 0.5);

        assertThat(e.getLifecycle()).isEqualTo(IngestedFileEntity.Lifecycle.INGESTED);
    }

    @Test
    void constructor_full_defaultsToVersionOne() {
        IngestedFileEntity e = new IngestedFileEntity(
                "sha1", "doc.pdf", "PDF", Instant.EPOCH, 5, "coll", 0.5);

        assertThat(e.getVersion()).isEqualTo(1);
    }

    @Test
    void constructor_full_defaultsToEmptyTags() {
        IngestedFileEntity e = new IngestedFileEntity(
                "sha1", "doc.pdf", "PDF", Instant.EPOCH, 5, "coll", 0.5);

        assertThat(e.getTags()).isEmpty();
    }

    // ── Constructeur legacy ───────────────────────────────────────────────────

    @Test
    void constructor_legacy_setsNullCollectionAndScore() {
        IngestedFileEntity e = new IngestedFileEntity("sha2", "f.txt", "TXT", Instant.EPOCH, 3);

        assertThat(e.getCollectionName()).isNull();
        assertThat(e.getQualityScore()).isNull();
    }

    @Test
    void constructor_legacy_lifecycleIsIngested() {
        IngestedFileEntity e = new IngestedFileEntity("sha2", "f.txt", "TXT", Instant.EPOCH, 3);
        assertThat(e.getLifecycle()).isEqualTo(IngestedFileEntity.Lifecycle.INGESTED);
    }

    // ── R2 — Lifecycle setters ────────────────────────────────────────────────

    @Test
    void setLifecycle_updatesField() {
        IngestedFileEntity e = entity();
        e.setLifecycle(IngestedFileEntity.Lifecycle.QUALIFIED);
        assertThat(e.getLifecycle()).isEqualTo(IngestedFileEntity.Lifecycle.QUALIFIED);
    }

    @Test
    void lifecycle_allValuesDistinct() {
        assertThat(IngestedFileEntity.Lifecycle.values()).hasSize(4);
        assertThat(IngestedFileEntity.Lifecycle.values())
                .containsExactlyInAnyOrder(
                        IngestedFileEntity.Lifecycle.INGESTED,
                        IngestedFileEntity.Lifecycle.QUALIFIED,
                        IngestedFileEntity.Lifecycle.TRAINED,
                        IngestedFileEntity.Lifecycle.ARCHIVED);
    }

    // ── R4 — Version ──────────────────────────────────────────────────────────

    @Test
    void setVersion_updatesField() {
        IngestedFileEntity e = entity();
        e.setVersion(3);
        assertThat(e.getVersion()).isEqualTo(3);
    }

    // ── R5 — Tags ─────────────────────────────────────────────────────────────

    @Test
    void setTags_updatesField() {
        IngestedFileEntity e = entity();
        e.setTags(List.of("kafka", "xml"));
        assertThat(e.getTags()).containsExactly("kafka", "xml");
    }

    @Test
    void setTags_null_setsEmptyList() {
        IngestedFileEntity e = entity();
        e.setTags(null);
        assertThat(e.getTags()).isEmpty();
    }

    // ── R7 — Quality score ────────────────────────────────────────────────────

    @Test
    void setQualityScore_updatesField() {
        IngestedFileEntity e = entity();
        e.setQualityScore(0.99);
        assertThat(e.getQualityScore()).isEqualTo(0.99);
    }

    @Test
    void setQualityScore_canBeNull() {
        IngestedFileEntity e = entity();
        e.setQualityScore(null);
        assertThat(e.getQualityScore()).isNull();
    }

    // ── Amélioration 1 — Machine à états ─────────────────────────────────────

    @Test
    void validateTransition_ingestedToQualified_ok() {
        IngestedFileEntity.Lifecycle.INGESTED.validateTransition(
                IngestedFileEntity.Lifecycle.QUALIFIED);
    }

    @Test
    void validateTransition_ingestedToTrained_throws() {
        assertThatThrownBy(() ->
                IngestedFileEntity.Lifecycle.INGESTED.validateTransition(
                        IngestedFileEntity.Lifecycle.TRAINED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Transition interdite");
    }

    @Test
    void validateTransition_qualifiedToTrained_ok() {
        IngestedFileEntity.Lifecycle.QUALIFIED.validateTransition(
                IngestedFileEntity.Lifecycle.TRAINED);
    }

    @Test
    void validateTransition_trainedToArchived_ok() {
        IngestedFileEntity.Lifecycle.TRAINED.validateTransition(
                IngestedFileEntity.Lifecycle.ARCHIVED);
    }

    @Test
    void validateTransition_archivedToIngested_ok() {
        IngestedFileEntity.Lifecycle.ARCHIVED.validateTransition(
                IngestedFileEntity.Lifecycle.INGESTED);
    }

    @Test
    void validateTransition_archivedToQualified_throws() {
        assertThatThrownBy(() ->
                IngestedFileEntity.Lifecycle.ARCHIVED.validateTransition(
                        IngestedFileEntity.Lifecycle.QUALIFIED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validateTransition_errorMessageContainsBothStates() {
        try {
            IngestedFileEntity.Lifecycle.INGESTED.validateTransition(
                    IngestedFileEntity.Lifecycle.TRAINED);
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("INGESTED").contains("TRAINED");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static IngestedFileEntity entity() {
        return new IngestedFileEntity("sha", "f.pdf", "PDF", Instant.EPOCH, 5, "coll", 0.5);
    }
}
