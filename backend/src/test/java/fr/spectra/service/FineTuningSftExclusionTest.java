package fr.spectra.service;

import fr.spectra.model.TrainingPair;
import fr.spectra.service.training.TrainingRunner;
import fr.spectra.persistence.FineTuningJobRepository;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Exclusion de paires du SFT via {@code spectra.fine-tuning.sft-excluded-categories}.
 *
 * <p>Depuis R8, le filtre porte aussi sur la catégorie du <b>document source</b> : c'est
 * ce qui permet d'écarter tout un thème du corpus d'entraînement (« pas de contractuel »)
 * sans raisonner sur la nature de chaque paire.</p>
 */
class FineTuningSftExclusionTest {

    @TempDir
    Path tempWorkDir;

    private FineTuningService serviceExcluding(String excludedCsv) {
        return new FineTuningService(
                mock(DatasetGeneratorService.class),
                mock(DpoGenerationService.class),
                mock(FineTuningJobRepository.class),
                mock(TrainingLogBroadcaster.class),
                mock(JobTelemetryStore.class),
                mock(ModelRegistryService.class),
                mock(EvaluationService.class),
                mock(BaseModelCatalog.class),
                mock(GedService.class),
                mock(IngestedFileRepository.class),
                "phi3",
                mock(TrainingRunner.class),
                tempWorkDir.toString(),
                tempWorkDir.resolve("models").toString(),
                excludedCsv);
    }

    private boolean excluded(FineTuningService service, TrainingPair pair) throws Exception {
        Method m = FineTuningService.class.getDeclaredMethod("isExcludedFromSft", TrainingPair.class);
        m.setAccessible(true);
        return (boolean) m.invoke(service, pair);
    }

    /** Paire de Q/R issue d'un document dont la catégorie est {@code documentCategory}. */
    private static TrainingPair qaPairFrom(String documentCategory) {
        return TrainingPair.of("Question ?", "Réponse.", "doc.pdf", "qa",
                "question_answer", 0.9, documentCategory);
    }

    @Test
    void noExclusionConfigured_keepsEverything() throws Exception {
        FineTuningService service = serviceExcluding("");

        assertThat(excluded(service, qaPairFrom("contractuel"))).isFalse();
    }

    @Test
    void excludesByDocumentCategory_evenWhenThePairItselfIsAPlainQa() throws Exception {
        // Le cas qui motive la fonctionnalité : la paire est une Q/R ordinaire (category="qa"),
        // seul son document d'origine la rattache au thème écarté.
        FineTuningService service = serviceExcluding("contractuel");

        assertThat(excluded(service, qaPairFrom("contractuel"))).isTrue();
        assertThat(excluded(service, qaPairFrom("procedures"))).isFalse();
    }

    @Test
    void documentCategoryMatchIsCaseInsensitive() throws Exception {
        FineTuningService service = serviceExcluding("contractuel");

        assertThat(excluded(service, qaPairFrom("Contractuel"))).isTrue();
    }

    @Test
    void unclassifiedDocument_isNeverExcludedByCategory() throws Exception {
        // documentCategory null : rien ne permet d'affirmer que la paire relève du thème
        // écarté, on la garde plutôt que d'amputer le corpus au hasard.
        FineTuningService service = serviceExcluding("contractuel");

        assertThat(excluded(service, qaPairFrom(null))).isFalse();
    }

    @Test
    void legacyExclusionByPairCategoryAndTypeStillApplies() throws Exception {
        FineTuningService service = serviceExcluding("negative,summarization");

        assertThat(excluded(service, TrainingPair.of("q", "r", "doc.pdf",
                "negative", "refusal", 0.9))).isTrue();
        assertThat(excluded(service, TrainingPair.of("q", "r", "doc.pdf",
                "summary", "summarization", 0.9))).isTrue();
        assertThat(excluded(service, TrainingPair.of("q", "r", "doc.pdf",
                "qa", "question_answer", 0.9))).isFalse();
    }
}
