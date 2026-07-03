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

    public void publish(String level, String message) {
        Map<String, Object> event = new HashMap<>();
        event.put("level", level);
        event.put("message", message);
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

    /**
     * Flux Reactor consommé par le SSE endpoint.
     * Le flux ne se termine jamais ; les clients se déconnectent quand ils le souhaitent.
     */
    public Flux<Map<String, Object>> stream() {
        return sink.asFlux();
    }
}
