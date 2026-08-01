package fr.spectra.service.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le runner HTTP doit reproduire <b>exactement</b> la sémantique du mode hôte : progression
 * ligne à ligne, code de sortie fidèle, et — surtout — aucune interruption prise pour un succès.
 *
 * <p>Le dernier point est le seul qui puisse causer un dégât durable. Un flux coupé sans
 * sentinelle signifie « résultat indéterminé » ; le convertir en code 0 ferait enregistrer au
 * registre un modèle qui n'a jamais fini d'être entraîné, et personne ne s'en apercevrait avant
 * de l'interroger.
 */
class HttpTrainingRunnerTest {

    private MockWebServer server;
    private HttpTrainingRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        runner = new HttpTrainingRunner(server.url("/").toString(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static TrainingSpec spec() {
        return new TrainingSpec("job-1", Path.of("/app/data/ft/dataset.jsonl"),
                Path.of("/app/data/ft/adapter"), "microsoft/Phi-3-mini-4k-instruct",
                64, 128, 3, 2e-4, true, false, true, 0.1);
    }

    private static MockResponse stream(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/plain").setBody(body);
    }

    // ── Disponibilité ────────────────────────────────────────────────────────

    @Test
    @DisplayName("/health confirme les scripts → disponible")
    void availableWhenHealthReportsScripts() {
        // Deux réponses : isAvailable() et unavailabilityReason() interrogent /health chacun.
        String healthy = "{\"status\":\"ok\",\"trainScript\":true,\"exportScript\":true}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(healthy));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(healthy));

        assertThat(runner.isAvailable()).isTrue();
        assertThat(runner.unavailabilityReason()).isNull();
    }

    @Test
    @DisplayName("trainer joignable mais sans script → indisponible, et le dit")
    void unavailableWhenTrainerLacksScripts() {
        // Cas réel : image du trainer construite sans scripts/. Le détecter ici, c'est refuser
        // la soumission au lieu d'échouer à la première commande.
        String noScript = "{\"status\":\"ok\",\"trainScript\":false}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(noScript));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(noScript));

        assertThat(runner.isAvailable()).isFalse();
        assertThat(runner.unavailabilityReason()).contains("script d'entraînement est absent");
    }

    @Test
    @DisplayName("trainer injoignable → indisponible, avec la marche à suivre")
    void unavailableWhenTrainerUnreachable() throws Exception {
        server.shutdown();   // plus personne n'écoute

        assertThat(runner.isAvailable()).isFalse();
        assertThat(runner.unavailabilityReason())
                .contains("injoignable")
                .contains("profil compose")          // comment le démarrer
                .contains("runner=process");         // ou comment revenir en arrière
    }

    // ── Entraînement ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("les lignes du script sont diffusées, la sentinelle ne l'est pas")
    void streamsLinesWithoutLeakingTheSentinel() throws Exception {
        server.enqueue(stream("epoch 1/3\nepoch 2/3\nepoch 3/3\n__EXIT__:0\n"));
        List<String> lines = new ArrayList<>();

        int exit = runner.train(spec(), lines::add, () -> false);

        assertThat(exit).isZero();
        // La sentinelle est un détail de transport : la diffuser en SSE la ferait apparaître
        // dans les journaux d'entraînement présentés à l'utilisateur.
        assertThat(lines).containsExactly("epoch 1/3", "epoch 2/3", "epoch 3/3");
    }

    @Test
    @DisplayName("le code de sortie non nul est remonté tel quel")
    void propagatesNonZeroExitCode() throws Exception {
        server.enqueue(stream("torch introuvable\n__EXIT__:42\n"));

        assertThat(runner.train(spec(), line -> { }, () -> false)).isEqualTo(42);
    }

    @Test
    @DisplayName("flux interrompu sans sentinelle → exception, jamais un succès")
    void missingSentinelIsAFailure() {
        // Conteneur tué, connexion rompue : le résultat est indéterminé. Le rendre comme un
        // code 0 enregistrerait un modèle jamais entraîné.
        server.enqueue(stream("epoch 1/3\nepoch 2/3\n"));

        assertThatThrownBy(() -> runner.train(spec(), line -> { }, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sans code de sortie")
                .hasMessageContaining("PAS considéré comme un succès");
    }

    @Test
    @DisplayName("refus HTTP du trainer → exception nommant le statut")
    void httpErrorIsReported() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("script absent"));

        assertThatThrownBy(() -> runner.train(spec(), line -> { }, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("503");
    }

    @Test
    @DisplayName("la requête porte les paramètres nommés, pas des positions")
    void sendsNamedParameters() throws Exception {
        server.enqueue(stream("__EXIT__:0\n"));

        runner.train(spec(), line -> { }, () -> false);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/train");
        @SuppressWarnings("unchecked")
        Map<String, Object> body =
                new ObjectMapper().readValue(request.getBody().readUtf8(), Map.class);
        assertThat(body).containsEntry("baseHfRepo", "microsoft/Phi-3-mini-4k-instruct")
                .containsEntry("loraRank", 64)
                .containsEntry("epochs", 3)
                .containsEntry("packing", true)
                .containsEntry("dpo", false)
                .containsEntry("orpo", true);
        assertThat(body.get("datasetFile").toString()).endsWith("dataset.jsonl");
    }

    // ── Annulation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("annulation en cours de flux → code 130, sans exception")
    void cancellationYieldsInterruptedCode() throws Exception {
        server.enqueue(stream("epoch 1/3\nepoch 2/3\nepoch 3/3\n__EXIT__:0\n"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"cancelled\":true}"));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<String> lines = new ArrayList<>();

        int exit = runner.train(spec(), line -> {
            lines.add(line);
            cancelled.set(true);   // annulé dès la première ligne
        }, cancelled::get);

        // 130 et non une exception : l'annulation est un dénouement attendu, pas une panne.
        assertThat(exit).isEqualTo(130);
        assertThat(lines).hasSize(1);
    }

    @Test
    @DisplayName("cancel() rend ce que le trainer rapporte")
    void cancelReportsTrainerAnswer() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"cancelled\":true}"));
        assertThat(runner.cancel("job-1")).isTrue();

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"cancelled\":false}"));
        assertThat(runner.cancel("job-inconnu")).isFalse();
    }

    @Test
    @DisplayName("trainer injoignable à l'annulation → false, sans propager d'exception")
    void cancelIsBestEffort() throws Exception {
        // Le job local passe FAILED de toute façon ; faire échouer cancelJob() sur une
        // indisponibilité réseau empêcherait l'utilisateur de solder un job déjà perdu.
        server.shutdown();

        assertThat(runner.cancel("job-1")).isFalse();
    }
}
