package fr.spectra.controller;

import fr.spectra.service.TaskActivityService;
import fr.spectra.service.TrainingLogBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

@RestController
@RequestMapping("/api/sse")
public class SseController {

    private static final Logger log = LoggerFactory.getLogger(SseController.class);

    private final TrainingLogBroadcaster broadcaster;
    private final TaskActivityService taskActivity;

    public SseController(TrainingLogBroadcaster broadcaster, TaskActivityService taskActivity) {
        this.broadcaster = broadcaster;
        this.taskActivity = taskActivity;
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

    /**
     * Activité globale des tâches de fond (ingestion, dataset, DPO, fine-tuning, évaluations,
     * A/B, installations, benchmarks) : instantané compact poussé à chaque changement d'état.
     * Le client reçoit l'état courant dès la connexion (pas d'attente du premier changement),
     * puis les instantanés qui diffèrent — remplace le polling REST multiple. Un battement de
     * cœur (ré-émission toutes les 25 s même sans changement) garde la connexion vivante à
     * travers les proxys et signale au client que le flux est bien actif.
     */
    @GetMapping(value = "/tasks", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> getTaskActivity() {
        return Flux.merge(
                        Mono.fromCallable(taskActivity::snapshot),
                        taskActivity.stream(),
                        Flux.interval(Duration.ofSeconds(25)).map(i -> taskActivity.snapshot()))
                .onErrorContinue((err, obj) ->
                        log.warn("SSE tasks error (ignored): {}", err.getMessage()));
    }
}
