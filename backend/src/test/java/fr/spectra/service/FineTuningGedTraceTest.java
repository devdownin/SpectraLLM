package fr.spectra.service;

import fr.spectra.persistence.DocumentModelLinkEntity;
import fr.spectra.persistence.FineTuningJobRepository;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Traçabilité GED en fin de fine-tuning : les documents sources du dataset sont liés
 * au modèle (TRAINED_ON) et leur cycle de vie avance vers TRAINED en respectant la
 * machine à états — ces liens n'étaient auparavant posés que manuellement via l'API.
 */
class FineTuningGedTraceTest {

    @TempDir
    Path tempWorkDir;

    private GedService gedService;
    private IngestedFileRepository fileRepo;
    private FineTuningService service;

    @BeforeEach
    void setUp() {
        gedService = mock(GedService.class);
        fileRepo = mock(IngestedFileRepository.class);
        service = new FineTuningService(
                mock(DatasetGeneratorService.class),
                mock(DpoGenerationService.class),
                mock(FineTuningJobRepository.class),
                mock(TrainingLogBroadcaster.class),
                mock(ModelRegistryService.class),
                mock(BaseModelCatalog.class),
                gedService,
                fileRepo,
                "phi3",
                tempWorkDir.toString(),
                "./scripts/train.sh",
                "./scripts/export_gguf.py",
                "python3",
                tempWorkDir.resolve("models").toString(),
                "");
    }

    private static IngestedFileEntity doc(String sha, IngestedFileEntity.Lifecycle lifecycle) {
        IngestedFileEntity e = new IngestedFileEntity(
                sha, "doc.pdf", "PDF", Instant.now(), 10, "spectra_documents", 0.8);
        e.setLifecycle(lifecycle);
        return e;
    }

    @Test
    void markDocumentsTrained_qualifiedDoc_linkedAndTransitionedToTrained() {
        IngestedFileEntity qualified = doc("sha-q", IngestedFileEntity.Lifecycle.QUALIFIED);
        when(fileRepo.findByFileName("doc.pdf")).thenReturn(List.of(qualified));

        service.markDocumentsTrained("job-1", "mon-modele", Set.of("doc.pdf"));

        verify(gedService).linkToModel("sha-q", "mon-modele",
                DocumentModelLinkEntity.LinkType.TRAINED_ON, "fine-tuning");
        verify(gedService).transitionLifecycle("sha-q",
                IngestedFileEntity.Lifecycle.TRAINED, "fine-tuning");
    }

    @Test
    void markDocumentsTrained_ingestedDoc_passesThroughQualified() {
        IngestedFileEntity ingested = doc("sha-i", IngestedFileEntity.Lifecycle.INGESTED);
        when(fileRepo.findByFileName("doc.pdf")).thenReturn(List.of(ingested));

        service.markDocumentsTrained("job-2", "mon-modele", Set.of("doc.pdf"));

        // Machine à états : INGESTED → QUALIFIED → TRAINED (pas de saut direct).
        var inOrder = inOrder(gedService);
        inOrder.verify(gedService).transitionLifecycle("sha-i",
                IngestedFileEntity.Lifecycle.QUALIFIED, "fine-tuning");
        inOrder.verify(gedService).transitionLifecycle("sha-i",
                IngestedFileEntity.Lifecycle.TRAINED, "fine-tuning");
    }

    @Test
    void markDocumentsTrained_archivedDoc_linkedButNotResurrected() {
        IngestedFileEntity archived = doc("sha-a", IngestedFileEntity.Lifecycle.ARCHIVED);
        when(fileRepo.findByFileName("doc.pdf")).thenReturn(List.of(archived));

        service.markDocumentsTrained("job-3", "mon-modele", Set.of("doc.pdf"));

        verify(gedService).linkToModel("sha-a", "mon-modele",
                DocumentModelLinkEntity.LinkType.TRAINED_ON, "fine-tuning");
        verify(gedService, never()).transitionLifecycle(anyString(), any(), anyString());
    }

    @Test
    void markDocumentsTrained_gedFailure_doesNotPropagate() {
        IngestedFileEntity qualified = doc("sha-e", IngestedFileEntity.Lifecycle.QUALIFIED);
        when(fileRepo.findByFileName("doc.pdf")).thenReturn(List.of(qualified));
        when(gedService.linkToModel(anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("GED indisponible"));

        // Best-effort : aucune exception ne doit remonter (le job resterait COMPLETED).
        service.markDocumentsTrained("job-4", "mon-modele", Set.of("doc.pdf"));
    }

    @Test
    void markDocumentsTrained_unknownSource_noInteraction() {
        when(fileRepo.findByFileName("absent.pdf")).thenReturn(List.of());

        service.markDocumentsTrained("job-5", "mon-modele", Set.of("absent.pdf"));

        verify(gedService, never()).linkToModel(anyString(), anyString(), any(), anyString());
    }
}
