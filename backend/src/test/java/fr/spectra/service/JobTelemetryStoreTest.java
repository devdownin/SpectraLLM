package fr.spectra.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trace persistante d'un job : ce qui survit à un rechargement de page.
 *
 * <p>Le flux SSE n'a pas de mémoire — il ne rejoue rien à un client qui arrive en cours de route,
 * et rien du tout pour un job terminé. Sans ce magasin, un travail de plusieurs heures perdait
 * l'intégralité de sa trace sur un F5 (constats S1 et S8 de
 * {@code docs/process/audit-suivi-finetuning-ui.fr.md}).</p>
 */
class JobTelemetryStoreTest {

    @TempDir
    Path workDir;

    private static final String JOB = "job-1";

    private JobTelemetryStore store() {
        return store(5_000_000, 2_000_000, 2_000);
    }

    private JobTelemetryStore store(long maxLogBytes, long maxLossBytes, int maxPoints) {
        return new JobTelemetryStore(workDir.toString(), maxLogBytes, maxLossBytes, maxPoints);
    }

    @Test
    @DisplayName("le journal se relit avec son horodatage, son niveau et son message")
    void logLinesRoundTrip() {
        JobTelemetryStore store = store();

        store.appendLog(JOB, "INFO", "  epoch=0.33  loss=1.8421");
        store.appendLog(JOB, "ERROR", "Job job-1 échoué : trainer injoignable");

        JobTelemetryStore.Telemetry telemetry = store.read(JOB, 500);
        assertThat(telemetry.logs()).hasSize(2);
        assertThat(telemetry.logs().get(0).message()).isEqualTo("  epoch=0.33  loss=1.8421");
        assertThat(telemetry.logs().get(0).timestamp()).matches("\\d{2}:\\d{2}:\\d{2}");
        assertThat(telemetry.logs().get(1).level()).isEqualTo("ERROR");
        assertThat(telemetry.capped()).isFalse();
    }

    @Test
    @DisplayName("la relecture rend la QUEUE du journal, et dit combien de lignes existent")
    void readReturnsTheTail() {
        JobTelemetryStore store = store();
        for (int i = 1; i <= 20; i++) {
            store.appendLog(JOB, "INFO", "ligne " + i);
        }

        JobTelemetryStore.Telemetry telemetry = store.read(JOB, 5);

        // Ce sont les DERNIÈRES lignes qui intéressent : celles qui expliquent où le job en est.
        assertThat(telemetry.logs()).hasSize(5);
        assertThat(telemetry.logs().get(0).message()).isEqualTo("ligne 16");
        assertThat(telemetry.logs().get(4).message()).isEqualTo("ligne 20");
        // Le total dit à l'UI que ce qu'elle affiche n'est pas le début du run.
        assertThat(telemetry.totalLogLines()).isEqualTo(20);
    }

    @Test
    @DisplayName("la série de perte conserve époque fractionnaire, loss et eval_loss")
    void lossSeriesRoundTrips() {
        JobTelemetryStore store = store();

        store.appendLossPoint(JOB, 0.33, 1.84, null);
        store.appendLossPoint(JOB, 1.0, 1.20, 1.35);

        var losses = store.read(JOB, 500).losses();
        assertThat(losses).hasSize(2);
        assertThat(losses.get(0).epoch()).isEqualTo(0.33);
        assertThat(losses.get(0).loss()).isEqualTo(1.84);
        assertThat(losses.get(0).evalLoss()).isNull();
        assertThat(losses.get(1).evalLoss()).isEqualTo(1.35);
    }

    @Test
    @DisplayName("une ligne sans valeur mesurée n'entre pas dans la série")
    void pointsWithoutValueAreNotWritten() {
        JobTelemetryStore store = store();

        store.appendLossPoint(JOB, 2.0, null, null);

        assertThat(store.read(JOB, 500).losses()).isEmpty();
    }

    @Test
    @DisplayName("une série trop longue est sous-échantillonnée, premier et dernier compris")
    void longSeriesIsDownsampled() {
        JobTelemetryStore store = store(5_000_000, 2_000_000, 10);
        for (int i = 1; i <= 100; i++) {
            store.appendLossPoint(JOB, i / 10.0, 2.0 - i / 100.0, null);
        }

        var losses = store.read(JOB, 500).losses();

        // `logging_steps=1` sur un run long produit des dizaines de milliers de points : ni le
        // réseau ni le graphe n'ont de raison de les transporter tous.
        assertThat(losses.size()).isLessThanOrEqualTo(11);
        assertThat(losses.get(0).epoch()).isEqualTo(0.1);
        assertThat(losses.get(losses.size() - 1).epoch()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("le journal cesse de croître à sa borne, et le signale une seule fois")
    void logStopsGrowingAtItsBudget() {
        JobTelemetryStore store = store(200, 2_000_000, 2_000);
        for (int i = 1; i <= 50; i++) {
            store.appendLog(JOB, "INFO", "une ligne de journal assez longue pour saturer, n°" + i);
        }

        JobTelemetryStore.Telemetry telemetry = store.read(JOB, 500);

        assertThat(telemetry.capped()).isTrue();
        // Sans le garde, chaque ligne suivante réécrirait un avertissement et le fichier
        // continuerait de croître — l'inverse de ce que la borne cherche à obtenir.
        assertThat(telemetry.logs().stream().filter(l -> l.message().contains("tronqué")).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("un job sans trace rend une trace vide, pas une erreur")
    void unknownJobYieldsEmptyTelemetry() {
        JobTelemetryStore.Telemetry telemetry = store().read("job-sans-trace", 500);

        // Job antérieur à ce service, ou dont le répertoire a été purgé : ce n'est pas une anomalie.
        assertThat(telemetry.logs()).isEmpty();
        assertThat(telemetry.losses()).isEmpty();
        assertThat(telemetry.totalLogLines()).isZero();
    }

    @Test
    @DisplayName("une ligne de série corrompue n'emporte pas le reste de la courbe")
    void corruptedLossLineIsSkipped() throws Exception {
        JobTelemetryStore store = store();
        store.appendLossPoint(JOB, 1.0, 1.5, null);
        Files.writeString(workDir.resolve(JOB).resolve("losses.jsonl"),
                "ceci n'est pas du JSON" + System.lineSeparator(),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        store.appendLossPoint(JOB, 2.0, 1.1, null);

        assertThat(store.read(JOB, 500).losses()).hasSize(2);
    }

    @Test
    @DisplayName("un identifiant de job qui sort du répertoire de travail est refusé")
    void traversalJobIdIsRejected() {
        // L'identifiant vient d'une variable de chemin HTTP : un resolve() nu suffirait à lire
        // n'importe quel fichier de la machine.
        assertThatThrownBy(() -> store().read("../../etc", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
