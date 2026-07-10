package fr.spectra.controller;

import fr.spectra.service.TrainingLogBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

@RestController
@RequestMapping("/api/sse")
public class SseController {

    private static final Logger log = LoggerFactory.getLogger(SseController.class);

    private final TrainingLogBroadcaster broadcaster;

    public SseController(TrainingLogBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * Charge système — heartbeat toutes les 5 s pour garder la connexion SSE vivante.
     * Les valeurs sont des placeholders : le vrai monitoring GPU nécessiterait nvidia-smi.
     */
    @GetMapping(value = "/system-load", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> getSystemLoad() {
        return Flux.interval(Duration.ofSeconds(5))
                .map(seq -> Map.<String, Object>of(
                        "timestamp", LocalTime.now().toString(),
                        "uptime", seq * 5
                ))
                .onErrorContinue((err, obj) ->
                        log.warn("SSE system-load error (ignored): {}", err.getMessage()));
    }

    /**
     * Flux des logs de fine-tuning en temps réel.
     * Ce flux ne contient que les events publiés par FineTuningService pendant un vrai job.
     * Il reste silencieux (aucun event) tant qu'aucun job ne tourne.
     * onErrorContinue garantit que le flux survit aux erreurs individuelles sans se terminer.
     */
    @GetMapping(value = "/training-logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> getTrainingLogs() {
        return broadcaster.stream()
                .onErrorContinue((err, obj) ->
                        log.warn("SSE training-logs error (ignored): {}", err.getMessage()));
    }
}
