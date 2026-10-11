package fr.spectra.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Journal des appels d'outils MCP : ce qu'un agent a demandé, quand, avec quel résultat.
 *
 * <p>Un agent appelle en boucle et ne laisse aucune trace dans l'interface : sans ce journal,
 * « pourquoi l'agent a-t-il répondu ça ? » n'a pas de réponse côté Spectra. Chaque appel donne
 * une entrée (outil, arguments résumés, issue, durée), un timer {@code spectra.mcp.calls}
 * étiqueté par outil et par issue, et une ligne de journal.</p>
 *
 * <p><b>En mémoire, borné, et c'est un choix.</b> Les {@value #CAPACITY} derniers appels, perdus
 * au redémarrage : c'est un outil de diagnostic, pas une piste d'audit. Les arguments sont des
 * questions d'utilisateurs ; les persister demanderait une politique de rétention que ce
 * journal n'a pas. Chaque valeur est tronquée à {@value #MAX_ARGUMENT_CHARS} caractères.</p>
 */
@Component
@ConditionalOnProperty(name = "spectra.mcp.enabled", havingValue = "true")
public class McpCallJournal {

    private static final Logger log = LoggerFactory.getLogger(McpCallJournal.class);

    static final int CAPACITY = 500;
    static final int MAX_ARGUMENT_CHARS = 200;
    static final String TIMER = "spectra.mcp.calls";

    public enum Outcome {
        /** L'outil a répondu. */
        OK,
        /** L'appel était invalide (question vide, collection inconnue) : à corriger côté agent. */
        CALLER_ERROR,
        /** Refusé par la limite de débit avant d'être exécuté : l'agent appelle trop vite. */
        RATE_LIMITED,
        /** Un service dont Spectra dépend n'a pas répondu : rien n'a été lu. */
        SERVICE_ERROR,
        /** Toute autre exception : un défaut à examiner dans le journal applicatif. */
        FAILURE
    }

    public record McpCall(Instant at, String tool, Map<String, String> arguments, Outcome outcome,
                          long durationMs, String detail) {}

    private final MeterRegistry registry;
    private final McpRateLimiter rateLimiter;
    private final Deque<McpCall> calls = new ArrayDeque<>(CAPACITY);

    public McpCallJournal(MeterRegistry registry, McpRateLimiter rateLimiter) {
        this.registry = registry;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Passe la limite de débit, exécute {@code work} et consigne l'appel, que l'outil réponde,
     * lève ou soit refusé. Le point d'entrée unique de chaque outil : c'est ce qui garantit
     * qu'aucun appel n'échappe ni à la limite ni au journal.
     */
    public <T> T record(String tool, Map<String, ?> arguments, Supplier<T> work) {
        Instant at = Instant.now();
        long start = System.nanoTime();
        try {
            rateLimiter.acquire(tool);
            T result = work.get();
            add(at, tool, arguments, Outcome.OK, start, null);
            return result;
        } catch (IllegalArgumentException e) {
            add(at, tool, arguments, Outcome.CALLER_ERROR, start, e.getMessage());
            throw e;
        } catch (McpRateLimiter.McpRateLimited e) {
            add(at, tool, arguments, Outcome.RATE_LIMITED, start, e.getMessage());
            throw e;
        } catch (McpToolFailure e) {
            add(at, tool, arguments, Outcome.SERVICE_ERROR, start, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            add(at, tool, arguments, Outcome.FAILURE, start, e.getClass().getSimpleName());
            throw e;
        }
    }

    /** Les {@code limit} derniers appels, le plus récent d'abord. */
    public List<McpCall> recent(int limit) {
        synchronized (calls) {
            return calls.stream().limit(Math.clamp(limit, 0, CAPACITY)).toList();
        }
    }

    private void add(Instant at, String tool, Map<String, ?> arguments, Outcome outcome, long start, String detail) {
        long nanos = System.nanoTime() - start;
        McpCall call = new McpCall(at, tool, summarise(arguments), outcome,
                Duration.ofNanos(nanos).toMillis(), truncate(detail));
        synchronized (calls) {
            if (calls.size() == CAPACITY) {
                calls.removeLast();
            }
            calls.addFirst(call);
        }
        Timer.builder(TIMER).tag("tool", tool).tag("outcome", outcome.name()).register(registry)
                .record(Duration.ofNanos(nanos));
        log.info("MCP {} {} en {} ms", tool, outcome, call.durationMs());
    }

    private static Map<String, String> summarise(Map<String, ?> arguments) {
        Map<String, String> summary = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((name, value) -> {
                if (value != null) {
                    summary.put(name, truncate(String.valueOf(value)));
                }
            });
        }
        return summary;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ARGUMENT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_ARGUMENT_CHARS) + "…";
    }
}
