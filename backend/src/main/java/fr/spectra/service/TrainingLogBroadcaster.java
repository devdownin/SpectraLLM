package fr.spectra.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Canal SSE pour les logs de fine-tuning en temps réel.
 *
 * Les composants internes appellent {@link #publish} ; le SSE endpoint
 * consomme {@link #stream()} pour broadcaster à tous les clients connectés.
 */
@Service
public class TrainingLogBroadcaster {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Sink multicast avec buffer de 500 messages.
     * onBackpressureBuffer garantit que les messages émis avant la connexion d'un
     * client ne sont pas perdus (dans la limite du buffer).
     *
     * autoCancel=false est CRUCIAL : sinon, à la déconnexion du dernier abonné SSE
     * le sink se termine définitivement et plus aucun log n'est diffusé (ni aux
     * clients suivants) jusqu'au redémarrage de l'application.
     */
    private final Sinks.Many<Map<String, Object>> sink =
            Sinks.many().multicast().onBackpressureBuffer(500, false);

    /** Évènement non rattaché à un job (message global). */
    public void publish(String level, String message) {
        publish(null, level, message);
    }

    /**
     * Évènement rattaché à un job.
     *
     * <p>Le canal est <b>unique et global</b> : tous les jobs y écrivent, et les clients ne
     * peuvent pas s'abonner à un job en particulier. Sans identifiant dans l'enveloppe, une page
     * affichant un job donné montrait donc la sortie de <i>n'importe quel</i> job en cours —
     * consulter un job terminé de la veille lui attribuait les lignes du run actuel. Le
     * {@code jobId} laisse le client filtrer ; {@code null} signifie « concerne tout le monde ».
     */
    public void publish(String jobId, String level, String message) {
        publish(jobId, level, message, null);
    }

    /** Évènement enrichi d'une charge utile structurée ({@code progress}), ou {@code null}. */
    public void publish(String jobId, String level, String message, Map<String, Object> progress) {
        Map<String, Object> event = new HashMap<>();
        event.put("level", level);
        event.put("message", message);
        event.put("jobId", jobId);
        event.put("progress", progress);
        event.put("timestamp", LocalTime.now().format(TIME_FMT));
        // publish() peut être appelé depuis plusieurs threads : retenter uniquement
        // en cas de contention d'émission (FAIL_NON_SERIALIZED) pour ne pas perdre
        // d'évènements ; abandonner sur les autres causes (pas de subscriber, annulé).
        sink.emitNext(event, (signalType, emitResult) ->
                emitResult == Sinks.EmitResult.FAIL_NON_SERIALIZED);
    }

    public void info(String message) {
        publish("INFO", message);
    }

    public void warn(String message) {
        publish("WARN", message);
    }

    public void error(String message) {
        publish("ERROR", message);
    }

    /** Ligne d'information attribuée à un job. */
    public void jobInfo(String jobId, String message) {
        publish(jobId, "INFO", message);
    }

    /** Avertissement attribué à un job. */
    public void jobWarn(String jobId, String message) {
        publish(jobId, "WARN", message);
    }

    /**
     * Progression d'entraînement : le message reste du texte lisible, mais l'évènement porte
     * <b>en plus</b> les valeurs exactes. Le client n'a ainsi plus à les réextraire du message
     * par expression régulière — troisième analyseur d'un format que personne n'avait écrit.
     */
    public void jobProgress(String jobId, String message,
                            double epoch, Double loss, Double evalLoss) {
        Map<String, Object> progress = new HashMap<>();
        progress.put("epoch", epoch);
        // Une valeur absente est une clé absente, et non un `null` : côté client, `null` devrait
        // être distingué de 0, qui est une loss parfaitement légitime.
        if (loss != null) {
            progress.put("loss", loss);
        }
        if (evalLoss != null) {
            progress.put("evalLoss", evalLoss);
        }
        publish(jobId, "INFO", message, progress);
    }

    /** Ligne d'erreur attribuée à un job. */
    public void jobError(String jobId, String message) {
        publish(jobId, "ERROR", message);
    }

    /**
     * Flux Reactor consommé par le SSE endpoint.
     * Le flux ne se termine jamais ; les clients se déconnectent quand ils le souhaitent.
     */
    public Flux<Map<String, Object>> stream() {
        return sink.asFlux();
    }
}
