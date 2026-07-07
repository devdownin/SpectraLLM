package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.InstallationJob;
import fr.spectra.dto.LlmFitRecommendation;
import fr.spectra.persistence.InstallationJobEntity;
import fr.spectra.persistence.InstallationJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sécurité : validation des arguments passés au sous-processus {@code llmfit}.
 *
 * <p>{@code ProcessBuilder(List)} n'utilise pas de shell, mais un argument commençant
 * par « - » serait interprété par llmfit comme une option (argument injection). Ces
 * valeurs venant de requêtes HTTP, {@code installModel} doit rejeter toute entrée hors
 * allowlist <b>avant</b> de démarrer le processus.
 */
class LlmFitServiceTest {

    private LlmFitService newService() {
        return new LlmFitService(
                new ObjectMapper(),
                mock(ModelRegistryService.class),
                mock(LlmChatClient.class),
                mock(InstallationJobRepository.class));
    }

    // ── Entrées rejetées (aucun sous-processus ne doit démarrer) ────────────────

    @Test
    void installModel_leadingDashModelName_isRejected() {
        assertThatThrownBy(() -> newService().installModel("--output=/etc/passwd", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelName");
    }

    @Test
    void installModel_shellMetacharsInModelName_isRejected() {
        assertThatThrownBy(() -> newService().installModel("model; rm -rf /", null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void installModel_nullModelName_isRejected() {
        assertThatThrownBy(() -> newService().installModel(null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void installModel_leadingDashQuant_isRejected() {
        assertThatThrownBy(() -> newService().installModel("valid-model", "--evil", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quant");
    }

    // ── Entrées légitimes acceptées (pas d'exception de validation) ─────────────

    @Test
    void installModel_huggingFaceStyleName_isAccepted() {
        // Ne doit PAS lever d'IllegalArgumentException ; renvoie un futur (l'exécution
        // asynchrone de llmfit échouera hors CI mais n'affecte pas la validation synchrone).
        assertThat(newService().installModel("TheBloke/Llama-2-7B-GGUF", "Q4_K_M", false)).isNotNull();
    }

    @Test
    void installModel_ollamaStyleName_isAccepted() {
        assertThat(newService().installModel("llama3.2:3b", null, false)).isNotNull();
    }

    // ── Recommandations : validation du simulateur et croisement registre ───────

    @Test
    void getRecommendations_simulationInvalide_rejetteEn400AuLieuDeListeVide() {
        // « 12 G » (espace) sort de l'allowlist : l'utilisateur doit voir une 400 claire,
        // pas un Model Hub silencieusement vide.
        assertThatThrownBy(() -> newService().getRecommendations(10, "12 G", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory");
    }

    @Test
    void enrichWithRegistry_modeleEnregistreDansSpectra_marqueInstalled() {
        ModelRegistryService registry = mock(ModelRegistryService.class);
        when(registry.listModels("chat")).thenReturn(List.of(
                Map.of("name", "llama3.2-3b-q4", "hfRepo", "llama3.2:3b")));
        LlmFitService service = new LlmFitService(new ObjectMapper(), registry, mock(LlmChatClient.class),
                mock(InstallationJobRepository.class));

        LlmFitRecommendation.ModelRecommendation installedViaSpectra = recommendation("llama3.2:3b", false);
        LlmFitRecommendation.ModelRecommendation notInstalled = recommendation("qwen2.5:7b", false);

        LlmFitRecommendation enriched = service.enrichWithRegistry(
                new LlmFitRecommendation(List.of(installedViaSpectra, notInstalled), null));

        assertThat(enriched.models().get(0).installed())
                .as("modèle présent dans le registre Spectra (hfRepo) → installé")
                .isTrue();
        assertThat(enriched.models().get(1).installed()).isFalse();
    }

    private static LlmFitRecommendation.ModelRecommendation recommendation(String name, boolean installed) {
        return new LlmFitRecommendation.ModelRecommendation(
                name, "provider", "3B", "chat", 90.0, "Good", "Q4_K_M",
                12.0, 3.5, 2.1, 4096, List.of(), Map.of(), List.of(), installed, "CPU");
    }

    // ── Suivi de progression (SSE) ───────────────────────────────────────────────

    @Test
    void getInstallationProgress_telechargementInconnu_fluxCompleteImmediatement() {
        // Reprise d'UI après redémarrage de l'API : aucun sink pour ce modèle.
        // Le flux doit se terminer (l'EventSource client se ferme) au lieu de rester muet.
        assertThat(newService().getInstallationProgress("inconnu")
                .collectList()
                .block(Duration.ofSeconds(2)))
                .isEmpty();
    }

    // ── Repli de détection du GGUF téléchargé (scan de models-dir) ──────────────

    @TempDir
    Path modelsDir;

    @Test
    void findRecentGguf_retourneLeGgufApparuDepuisLeDebutDuTelechargement() throws Exception {
        Instant start = Instant.now().minusSeconds(10);
        Path gguf = Files.writeString(modelsDir.resolve("nouveau-modele.gguf"), "fake");
        Files.writeString(modelsDir.resolve("autre-fichier.txt"), "pas un gguf");

        assertThat(LlmFitService.findRecentGguf(modelsDir, start)).contains(gguf);
    }

    @Test
    void findRecentGguf_ignoreLesGgufAnterieursAuTelechargement() throws Exception {
        Path ancien = Files.writeString(modelsDir.resolve("ancien.gguf"), "fake");
        Files.setLastModifiedTime(ancien,
                java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(3600)));

        assertThat(LlmFitService.findRecentGguf(modelsDir, Instant.now().minusSeconds(10))).isEmpty();
    }

    @Test
    void findRecentGguf_repertoireInexistant_retourneVide() {
        assertThat(LlmFitService.findRecentGguf(modelsDir.resolve("absent"), Instant.now())).isEmpty();
    }

    // ── Persistance des installations (H2) + réconciliation au démarrage ─────────

    private LlmFitService newServiceWith(InstallationJobRepository repo) {
        return new LlmFitService(new ObjectMapper(), mock(ModelRegistryService.class),
                mock(LlmChatClient.class), repo);
    }

    @Test
    void installModel_persisteUnJobPendingAvantLeSousProcessus() {
        InstallationJobRepository repo = mock(InstallationJobRepository.class);
        // findById renvoie vide : les transitions asynchrones (updateInstallation) sont des no-op,
        // on isole la persistance SYNCHRONE du job pending faite avant CompletableFuture.
        when(repo.findById(any())).thenReturn(Optional.empty());

        newServiceWith(repo).installModel("llama3.2:3b", "Q4_K_M", true);

        ArgumentCaptor<InstallationJobEntity> captor = ArgumentCaptor.forClass(InstallationJobEntity.class);
        verify(repo, atLeastOnce()).save(captor.capture());
        InstallationJob pending = captor.getAllValues().get(0).toDto();
        assertThat(pending.status()).isEqualTo(InstallationJob.Status.PENDING);
        assertThat(pending.modelName()).isEqualTo("llama3.2:3b");
        assertThat(pending.quant()).isEqualTo("Q4_K_M");
        assertThat(pending.autoActivate()).isTrue();
        assertThat(pending.jobId()).isNotBlank();
    }

    @Test
    void reconcileInterruptedInstallations_marqueFailedLesJobsNonTerminaux() {
        InstallationJobRepository repo = mock(InstallationJobRepository.class);
        InstallationJob downloading = InstallationJob.pending("j1", "modelA", null, false)
                .withProgress(42);
        InstallationJob completed = InstallationJob.pending("j2", "modelB", null, false)
                .completed("/models/b.gguf", "Terminé");
        InstallationJob alreadyFailed = InstallationJob.pending("j3", "modelC", null, false)
                .failed("boom");
        when(repo.findAll()).thenReturn(List.of(
                InstallationJobEntity.fromDto(downloading),
                InstallationJobEntity.fromDto(completed),
                InstallationJobEntity.fromDto(alreadyFailed)));

        newServiceWith(repo).reconcileInterruptedInstallations();

        ArgumentCaptor<InstallationJobEntity> captor = ArgumentCaptor.forClass(InstallationJobEntity.class);
        verify(repo, atLeastOnce()).save(captor.capture());
        // Seul le job DOWNLOADING doit être réécrit, en FAILED, sans perdre la progression.
        List<InstallationJob> saved = captor.getAllValues().stream().map(InstallationJobEntity::toDto).toList();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).jobId()).isEqualTo("j1");
        assertThat(saved.get(0).status()).isEqualTo(InstallationJob.Status.FAILED);
        assertThat(saved.get(0).progress()).isEqualTo(42);
        assertThat(saved.get(0).error()).contains("redémarrage");
    }

    @Test
    void reconcileInterruptedInstallations_aucunJobNonTerminal_neReecritRien() {
        InstallationJobRepository repo = mock(InstallationJobRepository.class);
        when(repo.findAll()).thenReturn(List.of(InstallationJobEntity.fromDto(
                InstallationJob.pending("j1", "m", null, false).completed("/m.gguf", "Terminé"))));

        newServiceWith(repo).reconcileInterruptedInstallations();

        verify(repo, never()).save(any());
    }

    @Test
    void getInstallations_trieLesPlusRecentesDAbord() {
        InstallationJobRepository repo = mock(InstallationJobRepository.class);
        InstallationJob vieux = new InstallationJob("old", InstallationJob.Status.COMPLETED,
                "m1", null, false, 100, "Terminé", "/m1.gguf", null,
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(590));
        InstallationJob recent = new InstallationJob("new", InstallationJob.Status.COMPLETED,
                "m2", null, false, 100, "Terminé", "/m2.gguf", null,
                Instant.now(), Instant.now());
        when(repo.findAll()).thenReturn(List.of(
                InstallationJobEntity.fromDto(vieux), InstallationJobEntity.fromDto(recent)));

        List<InstallationJob> jobs = newServiceWith(repo).getInstallations();

        assertThat(jobs).extracting(InstallationJob::jobId).containsExactly("new", "old");
    }
}
