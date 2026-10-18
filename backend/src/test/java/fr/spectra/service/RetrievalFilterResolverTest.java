package fr.spectra.service;

import fr.spectra.dto.RetrievalFilter;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.RetrievalFilterResolver.ResolvedFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalFilterResolverTest {

    private static final Instant JAN = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-15T00:00:00Z");

    private IngestedFileRepository repository;
    private RetrievalFilterResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(IngestedFileRepository.class);
        when(repository.findAllByOrderByIngestedAtDesc()).thenReturn(List.of(
                doc("sha-a", "contrat.pdf", JUN, null, "Juridique"),
                doc("sha-b", "notice.pdf", JAN, null, "Technique"),
                doc("sha-c", "autre.pdf", JUN, "projets", "Juridique")));
        resolver = new RetrievalFilterResolver(repository);
    }

    private static IngestedFileEntity doc(String sha, String name, Instant at, String collection, String category) {
        IngestedFileEntity e = new IngestedFileEntity(sha, name, "pdf", at, 3, collection, null);
        e.setCategories(List.of(category));
        return e;
    }

    @Test
    void filtreVide_inactif() {
        assertThat(resolver.resolve(RetrievalFilter.NONE, "spectra_documents", true)).isSameAs(ResolvedFilter.NONE);
        assertThat(resolver.resolve(null, "spectra_documents", true).active()).isFalse();
    }

    @Test
    void sourcesSeules_sansLectureDeLaGed() {
        ResolvedFilter f = resolver.resolve(new RetrievalFilter(null, List.of("a.pdf"), null, null), "c", false);

        assertThat(f.sha256s()).isNull();
        assertThat(f.sourceFiles()).containsExactly("a.pdf");
        verify(repository, never()).findAllByOrderByIngestedAtDesc();
    }

    @Test
    void categorie_insensibleALaCasse_etLimiteeALaCollection() {
        ResolvedFilter f = resolver.resolve(
                new RetrievalFilter(List.of("juridique"), null, null, null), "spectra_documents", true);

        // sha-c appartient à la collection « projets » : exclu.
        assertThat(f.sha256s()).containsExactly("sha-a");
        assertThat(f.sourceFiles()).containsExactly("contrat.pdf");
    }

    @Test
    void ficheSansCollection_nAppartientQuALaCollectionParDefaut() {
        ResolvedFilter f = resolver.resolve(
                new RetrievalFilter(List.of("technique"), null, null, null), "projets", false);

        assertThat(f.matchesNothing()).isTrue();
    }

    @Test
    void bornesDeDate_apresIncluse_avantExclue() {
        assertThat(resolver.resolve(new RetrievalFilter(null, null, JUN, null), "spectra_documents", true).sha256s())
                .containsExactly("sha-a");
        assertThat(resolver.resolve(new RetrievalFilter(null, null, null, JUN), "spectra_documents", true).sha256s())
                .containsExactly("sha-b");
    }

    @Test
    void chromaWhere_combineEmpreintesEtSources() {
        ResolvedFilter both = new ResolvedFilter(new LinkedHashSet<>(List.of("s1")), new LinkedHashSet<>(List.of("f1")));

        assertThat(both.chromaWhere()).isEqualTo(Map.of("$or", List.of(
                Map.of("sha256", Map.of("$in", List.of("s1"))),
                Map.of("sourceFile", Map.of("$in", List.of("f1"))))));
        assertThat(new ResolvedFilter(null, Set.of("f1")).chromaWhere())
                .isEqualTo(Map.of("sourceFile", Map.of("$in", List.of("f1"))));
        assertThat(ResolvedFilter.NONE.chromaWhere()).isNull();
    }

    @Test
    void accepts_parEmpreinte_puisParSourcePourLesChunksHistoriques() {
        ResolvedFilter f = new ResolvedFilter(Set.of("s1"), Set.of("f1"));

        assertThat(f.accepts("s1", "autre")).isTrue();
        // Empreinte présente mais hors filtre : la source homonyme ne suffit pas.
        assertThat(f.accepts("s2", "f1")).isFalse();
        assertThat(f.accepts(null, "f1")).isTrue();
        assertThat(f.accepts(null, "f2")).isFalse();
        assertThat(ResolvedFilter.NONE.accepts(null, null)).isTrue();
    }

    @Test
    void matchesNothing_seulementSiActifEtVide() {
        assertThat(new ResolvedFilter(Set.of(), Set.of()).matchesNothing()).isTrue();
        assertThat(new ResolvedFilter(Set.of(), Set.of("f")).matchesNothing()).isFalse();
        assertThat(ResolvedFilter.NONE.matchesNothing()).isFalse();
    }
}
