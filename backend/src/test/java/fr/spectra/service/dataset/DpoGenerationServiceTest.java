package fr.spectra.service.dataset;

import fr.spectra.model.DpoPair;
import fr.spectra.service.LlmChatClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Tests du chemin « préférence A/B → paire DPO » de {@link DpoGenerationService}. */
class DpoGenerationServiceTest {

    private DpoGenerationService newService(Path dir) {
        return new DpoGenerationService(mock(DatasetGeneratorService.class), mock(LlmChatClient.class), dir.toString());
    }

    @Test
    void addPreferencePair_persistsAndExposesPair(@TempDir Path dir) {
        DpoGenerationService svc = newService(dir);

        DpoPair pair = svc.addPreferencePair("Question ?", "Bonne réponse.", "Réponse variante.", "ab:without-rerank");

        assertThat(pair).isNotNull();
        assertThat(pair.chosen()).isEqualTo("Bonne réponse.");
        assertThat(pair.rejected()).isEqualTo("Réponse variante.");
        assertThat(pair.category()).isEqualTo("playground-ab");
        assertThat(pair.source()).isEqualTo("ab:without-rerank");

        assertThat(svc.getPreferencePairs()).hasSize(1);
        assertThat(svc.getAllPairs()).contains(pair); // fusionné avec les paires générées

        // Fichier séparé de dpo_pairs.jsonl.
        assertThat(Files.exists(dir.resolve("dpo_preference_pairs.jsonl"))).isTrue();
        assertThat(Files.exists(dir.resolve("dpo_pairs.jsonl"))).isFalse();
    }

    @Test
    void addPreferencePair_rejectsInvalidInput(@TempDir Path dir) {
        DpoGenerationService svc = newService(dir);

        assertThat(svc.addPreferencePair(null, "c", "r", "s")).isNull();
        assertThat(svc.addPreferencePair("p", "  ", "r", "s")).isNull();
        assertThat(svc.addPreferencePair("p", "same", "same", "s")).isNull(); // chosen == rejected
        assertThat(svc.getPreferencePairs()).isEmpty();
        assertThat(Files.exists(dir.resolve("dpo_preference_pairs.jsonl"))).isFalse();
    }

    @Test
    void addPreferencePair_appendsMultiple(@TempDir Path dir) throws Exception {
        DpoGenerationService svc = newService(dir);
        svc.addPreferencePair("q1", "c1", "r1", "ab:without-hybrid");
        svc.addPreferencePair("q2", "c2", "r2", "ab:without-corrective");

        assertThat(svc.getPreferencePairs()).hasSize(2);
        List<String> lines = Files.readAllLines(dir.resolve("dpo_preference_pairs.jsonl"));
        assertThat(lines).hasSize(2);
    }

    @Test
    void exportJsonl_includesPreferencePairs(@TempDir Path dir) throws Exception {
        DpoGenerationService svc = newService(dir);
        svc.addPreferencePair("q", "chosen", "rejected", "ab:without-rerank");

        Path exported = svc.exportJsonl();
        List<String> lines = Files.readAllLines(exported);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("\"chosen\":\"chosen\"").contains("playground-ab");
    }
}
