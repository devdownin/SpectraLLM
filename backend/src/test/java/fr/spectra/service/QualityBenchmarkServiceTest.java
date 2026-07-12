package fr.spectra.service;

import fr.spectra.dto.QualityCompareJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comparaison qualité asynchrone (suivie) : cycle de vie du job, réconciliation au démarrage
 * et validation des entrées. Le benchmark tenu à l'écart (ressource classpath) est réellement
 * chargé ; les appels LLM sont mockés pour rester déterministes et rapides.
 */
class QualityBenchmarkServiceTest {

    @TempDir
    Path workDir;

    private QualityBenchmarkService newService(LlmChatClient chat) {
        return newService(chat, "");
    }

    private QualityBenchmarkService newService(LlmChatClient chat, String judgeModel) {
        // benchmarkPath vide → ressource embarquée benchmarks/highway_benchmark.jsonl.
        return new QualityBenchmarkService(chat, new ModelSwitchCoordinator(chat, 2, 1),
                judgeModel, "", workDir.toString());
    }

    /** Chat mocké : réponse JSON valide pour le juge, texte quelconque pour le modèle évalué. */
    private LlmChatClient deterministicChat(String activeModel) {
        LlmChatClient chat = mock(LlmChatClient.class);
        java.util.concurrent.atomic.AtomicReference<String> active =
                new java.util.concurrent.atomic.AtomicReference<>(activeModel);
        when(chat.getActiveModel()).thenAnswer(i -> active.get());
        org.mockito.Mockito.doAnswer(i -> { active.set(i.getArgument(0)); return null; })
                .when(chat).setActiveModel(anyString());
        // Serveur "convergé" immédiatement : l'attente de convergence du coordinateur passe.
        when(chat.checkHealth()).thenReturn(new fr.spectra.dto.ServiceStatus(
                "llama-cpp", "http://test", true, "ok", 1, java.util.Map.of()));
        when(chat.chat(anyString(), anyString())).thenAnswer(inv -> {
            String system = inv.getArgument(0);
            if (system.contains("évaluateur expert")) {
                return "{\"score\": 8, \"justification\": \"ok\"}";
            }
            if (system.contains("N'EST PAS disponible")) {
                return "{\"refused\": true, \"justification\": \"s'abstient\"}";
            }
            return "Réponse du modèle évalué.";
        });
        return chat;
    }

    @Test
    void submitCompare_lifecycle_produitUnJobCompletedAvecLesDeuxRapports() {
        // Sans contexte Spring, self==null → runCompareAsync s'exécute en synchrone dans ce thread :
        // à la fin de submitCompare, le job est déjà terminal.
        QualityBenchmarkService service = newService(deterministicChat("modele-precedent"));
        service.init(); // câble la persistance (pas de @PostConstruct hors Spring)

        String jobId = service.submitCompare("modele-precedent", "modele-nouveau");

        assertThat(jobId).isNotBlank();
        QualityCompareJob job = service.getCompareJob(jobId);
        assertThat(job.status()).isEqualTo(QualityCompareJob.Status.COMPLETED);
        assertThat(job.baseline()).isEqualTo("modele-precedent");
        assertThat(job.candidate()).isEqualTo("modele-nouveau");
        assertThat(job.baselineReport()).isNotNull();
        assertThat(job.candidateReport()).isNotNull();
        // Toutes les questions answerable notées 8/10, toutes les non-answerable correctement refusées.
        assertThat(job.candidateReport().avgScore()).isEqualTo(8.0);
        assertThat(job.candidateReport().hallucinationRate()).isEqualTo(0.0);
        // Sans juge neutre configuré, chaque modèle s'auto-juge — tracé dans le rapport.
        assertThat(job.candidateReport().judgeModel()).isEqualTo("modele-nouveau");
        // Le job est persisté sur disque (survit à un redémarrage).
        assertThat(Files.exists(workDir.resolve("quality-compare-jobs.json"))).isTrue();
    }

    @Test
    void run_avecJugeNeutre_generePuisNoteEnDeuxPhases() {
        // Juge neutre configuré : génération avec le modèle évalué, puis UNE bascule vers le
        // juge pour noter, puis restauration du modèle initial — même schéma qu'EvaluationService.
        LlmChatClient chat = deterministicChat("modele-initial");
        QualityBenchmarkService service = newService(chat, "judge-x");

        var report = service.run("modele-evalue");

        assertThat(report.model()).isEqualTo("modele-evalue");
        assertThat(report.judgeModel()).isEqualTo("judge-x");
        assertThat(report.avgScore()).isEqualTo(8.0);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(chat);
        order.verify(chat).setActiveModel("modele-evalue");
        order.verify(chat).setActiveModel("judge-x");
        order.verify(chat).setActiveModel("modele-initial");
        assertThat(chat.getActiveModel()).isEqualTo("modele-initial");
    }

    @Test
    void submitCompare_argumentsVides_rejetes() {
        QualityBenchmarkService service = newService(mock(LlmChatClient.class));
        assertThatThrownBy(() -> service.submitCompare("", "candidate"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.submitCompare("baseline", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void init_marqueFailedLesJobsNonTerminauxPersistes() throws Exception {
        // Simule un job RUNNING laissé par une JVM tuée : le fichier existe au (re)démarrage.
        String running = """
                {
                  "orphan-1": {
                    "jobId": "orphan-1",
                    "status": "RUNNING",
                    "baseline": "a",
                    "candidate": "b",
                    "currentStep": "Évaluation…",
                    "baselineReport": null,
                    "candidateReport": null,
                    "error": null,
                    "createdAt": "2026-07-07T10:00:00Z",
                    "completedAt": null
                  }
                }
                """;
        Files.writeString(workDir.resolve("quality-compare-jobs.json"), running);

        QualityBenchmarkService service = newService(mock(LlmChatClient.class));
        service.init(); // @PostConstruct explicitement (pas de contexte Spring en test unitaire)

        QualityCompareJob job = service.getCompareJob("orphan-1");
        assertThat(job).isNotNull();
        assertThat(job.status()).isEqualTo(QualityCompareJob.Status.FAILED);
        assertThat(job.error()).contains("redémarrage");
    }
}
