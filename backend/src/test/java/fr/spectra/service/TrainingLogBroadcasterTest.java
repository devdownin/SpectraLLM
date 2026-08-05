package fr.spectra.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enveloppe des évènements diffusés sur {@code /api/sse/training-logs}.
 *
 * <p>Le canal est unique et global : tous les jobs y écrivent. Sans identifiant dans l'enveloppe,
 * un client ne peut pas distinguer la sortie du job qu'il affiche de celle d'un autre — c'est ce
 * qui faisait qu'un job terminé de la veille se voyait attribuer les lignes du run en cours
 * (constat S9 de {@code docs/process/audit-suivi-finetuning-ui.fr.md}).</p>
 */
class TrainingLogBroadcasterTest {

    private final TrainingLogBroadcaster broadcaster = new TrainingLogBroadcaster();

    /** Collecte les évènements reçus par un abonné, comme le fait le contrôleur SSE. */
    private List<Map<String, Object>> subscribe() {
        List<Map<String, Object>> received = new ArrayList<>();
        Disposable subscription = broadcaster.stream().subscribe(received::add);
        assertThat(subscription.isDisposed()).isFalse();
        return received;
    }

    @Test
    @DisplayName("une ligne de job porte son identifiant")
    void jobLinesCarryTheirJobId() {
        List<Map<String, Object>> received = subscribe();

        broadcaster.jobInfo("job-42", "  epoch=0.33  loss=1.8421");

        assertThat(received).hasSize(1);
        assertThat(received.get(0))
                .containsEntry("jobId", "job-42")
                .containsEntry("level", "INFO")
                .containsEntry("message", "  epoch=0.33  loss=1.8421")
                .containsKey("timestamp");
    }

    @Test
    @DisplayName("une erreur de job porte son identifiant et son niveau")
    void jobErrorsAreLabelled() {
        List<Map<String, Object>> received = subscribe();

        broadcaster.jobError("job-42", "Job job-42 échoué : trainer injoignable");

        assertThat(received).hasSize(1);
        assertThat(received.get(0))
                .containsEntry("jobId", "job-42")
                .containsEntry("level", "ERROR");
    }

    @Test
    @DisplayName("un message global n'est rattaché à aucun job")
    void globalMessagesHaveNoJobId() {
        List<Map<String, Object>> received = subscribe();

        broadcaster.info("message qui concerne tout le monde");

        // La clé doit exister avec une valeur nulle : le client distingue ainsi « pas de job »
        // d'un champ qu'il aurait oublié de lire.
        assertThat(received.get(0)).containsKey("jobId");
        assertThat(received.get(0).get("jobId")).isNull();
    }
}
