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

    // ── Verrou par modèle : pas de double téléchargement concurrent ─────────────

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({
            org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    void installModel_memeModeleDejaEnTelechargement_rejeteEnConflit() throws Exception {
        // Sous-processus factice qui « télécharge » 5 s : le verrou reste posé pendant ce temps.
        LlmFitService service = newService();
        Path slow = modelsDir.resolve("slow-llmfit.sh");
        Files.writeString(slow, "#!/bin/sh\nsleep 5\n");
        assertThat(slow.toFile().setExecutable(true)).isTrue();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "llmfitPath", slow.toString());

        service.installModel("llama3.2:3b", null, false);

        // Même modèle pendant le téléchargement → 409 (deux sous-processus écriraient le
        // même fichier cible et le repli findRecentGguf pourrait croiser les alias).
        assertThatThrownBy(() -> service.installModel("llama3.2:3b", null, false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("déjà en cours");

        // Un modèle DIFFÉRENT reste autorisé en parallèle.
        assertThat(service.installModel("qwen2.5:7b", null, false)).isNotNull();
    }

    // ── Fin d'installation : déduplication du cache llmfit et faux succès ───────

    /** Dépôt en mémoire : save/findById réels pour observer les transitions du job. */
    private InstallationJobRepository inMemoryRepo(Map<String, InstallationJobEntity> store) {
        InstallationJobRepository repo = mock(InstallationJobRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            InstallationJobEntity e = inv.getArgument(0);
            store.put(e.toDto().jobId(), e);
            return e;
        });
        when(repo.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        return repo;
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({
            org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    void installModel_ggufDansLeCacheLlmfit_estDeplaceSansLaisserDeDoublon() throws Exception {
        // Cache llmfit simulé : le GGUF téléchargé n'est PAS dans models-dir. Après
        // installation, il doit être DÉPLACÉ (pas copié) — sinon chaque modèle occupe
        // deux fois sa taille et le rapport de stockage (limité à models-dir) ne le voit pas.
        Path cache  = Files.createDirectories(modelsDir.resolve("llmfit-cache"));
        Path source = Files.writeString(cache.resolve("modele.Q4_K_M.gguf"), "poids");
        Path target = modelsDir.resolve("models");
        Path fake = modelsDir.resolve("fake-llmfit.sh");
        Files.writeString(fake, "#!/bin/sh\necho \"Download complete: " + source + "\"\nexit 0\n");
        assertThat(fake.toFile().setExecutable(true)).isTrue();

        Map<String, InstallationJobEntity> store = new java.util.concurrent.ConcurrentHashMap<>();
        LlmFitService service = newServiceWith(inMemoryRepo(store));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "llmfitPath", fake.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelsDirPath", target.toString());

        assertThat(service.installModel("llama3.2:3b", null, false)
                .get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(source).as("la source du cache llmfit ne doit pas survivre").doesNotExist();
        assertThat(target.resolve("modele.Q4_K_M.gguf")).hasContent("poids");
        InstallationJob job = store.values().iterator().next().toDto();
        assertThat(job.status()).isEqualTo(InstallationJob.Status.COMPLETED);
        assertThat(job.outputPath())
                .isEqualTo(target.resolve("modele.Q4_K_M.gguf").toAbsolutePath().toString());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({
            org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    void installModel_exit0SansGgufDetecte_marqueLeJobFailedPasCompleted() throws Exception {
        // llmfit sort en succès mais ni sa sortie ni le scan de models-dir ne révèlent de
        // GGUF : le modèle n'est ni copié, ni enregistré, ni activable. Le job doit être
        // FAILED — un COMPLETED afficherait un faux succès en vert dans l'historique.
        Path fake = modelsDir.resolve("fake-llmfit.sh");
        Files.writeString(fake, "#!/bin/sh\necho \"done\"\nexit 0\n");
        assertThat(fake.toFile().setExecutable(true)).isTrue();

        Map<String, InstallationJobEntity> store = new java.util.concurrent.ConcurrentHashMap<>();
        LlmFitService service = newServiceWith(inMemoryRepo(store));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "llmfitPath", fake.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelsDirPath",
                modelsDir.resolve("empty-models").toString());

        assertThat(service.installModel("llama3.2:3b", null, false)
                .get(30, java.util.concurrent.TimeUnit.SECONDS)).isFalse();

        InstallationJob job = store.values().iterator().next().toDto();
        assertThat(job.status()).isEqualTo(InstallationJob.Status.FAILED);
        assertThat(job.error()).contains("GGUF introuvable");
    }

    // ── Cache llmfit : inventaire et purge des doublons ─────────────────────────

    /** Service dont models-dir et cache-dir pointent vers des sous-répertoires du @TempDir. */
    private LlmFitService serviceWithDirs(Path models, Path cache) {
        LlmFitService service = newService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelsDirPath", models.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "llmfitCacheDirPath", cache.toString());
        return service;
    }

    @Test
    void getStorageReport_inventorieLeCacheLlmfitEtMarqueLesDoublons() throws Exception {
        Path models = Files.createDirectories(modelsDir.resolve("models"));
        Path cache  = Files.createDirectories(modelsDir.resolve("cache"));
        Files.writeString(models.resolve("a.gguf"), "poids-a");
        Files.writeString(cache.resolve("a.gguf"), "poids-a");            // doublon (même nom, même taille)
        Files.writeString(cache.resolve("partiel.gguf"), "tronc");        // inconnu de models-dir
        Files.writeString(cache.resolve("notes.txt"), "pas un gguf");

        Map<String, Object> report = serviceWithDirs(models, cache).getStorageReport();

        @SuppressWarnings("unchecked")
        Map<String, Object> cacheReport = (Map<String, Object>) report.get("llmfitCache");
        assertThat(cacheReport.get("overlapsModelsDir")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) cacheReport.get("files");
        assertThat(files).hasSize(2);
        assertThat(files).anySatisfy(f -> {
            assertThat(f.get("file")).isEqualTo("a.gguf");
            assertThat(f.get("duplicate")).isEqualTo(true);
        });
        assertThat(files).anySatisfy(f -> {
            assertThat(f.get("file")).isEqualTo("partiel.gguf");
            assertThat(f.get("duplicate")).isEqualTo(false);
        });
        assertThat(cacheReport.get("duplicateBytes")).isEqualTo((long) "poids-a".length());
    }

    @Test
    void purgeLlmfitCacheDuplicates_supprimeSeulementLesDoublons() throws Exception {
        Path models = Files.createDirectories(modelsDir.resolve("models"));
        Path cache  = Files.createDirectories(modelsDir.resolve("cache"));
        Files.writeString(models.resolve("a.gguf"), "poids-a");
        Files.writeString(cache.resolve("a.gguf"), "poids-a");            // doublon → supprimé
        Files.writeString(cache.resolve("partiel.gguf"), "tronc");        // conservé (pas dans models-dir)
        // Même nom mais taille différente = téléchargement partiel → conservé.
        Files.writeString(models.resolve("b.gguf"), "poids-b-complet");
        Files.writeString(cache.resolve("b.gguf"), "poids");

        Map<String, Object> result = serviceWithDirs(models, cache).purgeLlmfitCacheDuplicates();

        assertThat(result.get("deletedCount")).isEqualTo(1);
        assertThat(result.get("freedBytes")).isEqualTo((long) "poids-a".length());
        assertThat(cache.resolve("a.gguf")).doesNotExist();
        assertThat(cache.resolve("partiel.gguf")).exists();
        assertThat(cache.resolve("b.gguf")).exists();
        assertThat(models.resolve("a.gguf")).as("models-dir intact").exists();
    }

    @Test
    void purgeLlmfitCacheDuplicates_cacheEtModelsDirConfondus_neSupprimeRien() throws Exception {
        // Si llmfit télécharge DIRECTEMENT dans models-dir, chaque fichier serait son
        // propre « doublon » : la purge doit refuser au lieu de supprimer les modèles servis.
        Path models = Files.createDirectories(modelsDir.resolve("models"));
        Files.writeString(models.resolve("a.gguf"), "poids-a");

        Map<String, Object> result = serviceWithDirs(models, models).purgeLlmfitCacheDuplicates();

        assertThat(result.get("deletedCount")).isEqualTo(0);
        assertThat(result.get("skippedReason").toString()).contains("recouvrent");
        assertThat(models.resolve("a.gguf")).exists();
    }

    @Test
    void moveToSharedVolume_deplaceLeFichier_laSourceDisparait() throws Exception {
        Path source = Files.writeString(modelsDir.resolve("a.gguf"), "poids");
        Path target = Files.createDirectories(modelsDir.resolve("volume")).resolve("a.gguf");

        LlmFitService.moveToSharedVolume(source, target);

        assertThat(source).doesNotExist();
        assertThat(target).hasContent("poids");
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

    @Test
    void findRecentGguf_prefereLeFichierCorrespondantAuModeleDemande() throws Exception {
        // Deux téléchargements parallèles : le GGUF de l'AUTRE modèle est le plus récent.
        // Sans corrélation par nom, l'alias serait enregistré vers le mauvais fichier.
        Instant start = Instant.now().minusSeconds(10);
        Path wanted = Files.writeString(modelsDir.resolve("llama-2-7b.Q4_K_M.gguf"), "fake");
        Path other  = Files.writeString(modelsDir.resolve("qwen2.5-7b-instruct-q4_k_m.gguf"), "fake");
        Files.setLastModifiedTime(other,
                java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(5)));

        assertThat(LlmFitService.findRecentGguf(modelsDir, start, "TheBloke/Llama-2-7B-GGUF"))
                .contains(wanted);
    }

    @Test
    void findRecentGguf_aucuneCorrespondanceDeNom_repliSurLePlusRecent() throws Exception {
        Instant start = Instant.now().minusSeconds(10);
        Files.writeString(modelsDir.resolve("ancien-inconnu.gguf"), "fake");
        Path recent = Files.writeString(modelsDir.resolve("recent-inconnu.gguf"), "fake");
        Files.setLastModifiedTime(recent,
                java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(5)));

        assertThat(LlmFitService.findRecentGguf(modelsDir, start, "org/ModeleSansFichier"))
                .contains(recent);
    }

    // ── Persistance des installations (H2) + réconciliation au démarrage ─────────

    private LlmFitService newServiceWith(InstallationJobRepository repo) {
        return new LlmFitService(new ObjectMapper(), mock(ModelRegistryService.class),
                mock(LlmChatClient.class), repo);
    }

    @Test
    void installationJob_previousActiveModel_survitAuRoundTripEntity() {
        // Le modèle actif remplacé (baseline du benchmark qualité) doit traverser les withers,
        // le mapping entité et la persistance sans se perdre.
        InstallationJob job = InstallationJob.pending("j", "m", "Q4", true)
                .withPreviousActiveModel("ancien-actif")
                .completed("/m.gguf", "Terminé");
        InstallationJob roundTrip = InstallationJobEntity.fromDto(job).toDto();
        assertThat(roundTrip.previousActiveModel()).isEqualTo("ancien-actif");
        assertThat(roundTrip.status()).isEqualTo(InstallationJob.Status.COMPLETED);
        assertThat(roundTrip.outputPath()).isEqualTo("/m.gguf");
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
                "m1", null, false, 100, "Terminé", "/m1.gguf", null, null,
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(590));
        InstallationJob recent = new InstallationJob("new", InstallationJob.Status.COMPLETED,
                "m2", null, false, 100, "Terminé", "/m2.gguf", null, null,
                Instant.now(), Instant.now());
        when(repo.findAll()).thenReturn(List.of(
                InstallationJobEntity.fromDto(vieux), InstallationJobEntity.fromDto(recent)));

        List<InstallationJob> jobs = newServiceWith(repo).getInstallations();

        assertThat(jobs).extracting(InstallationJob::jobId).containsExactly("new", "old");
    }
}
