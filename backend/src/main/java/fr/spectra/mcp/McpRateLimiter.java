package fr.spectra.mcp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * Limite le débit des appels d'outils MCP : un seau à jetons pour l'instance.
 *
 * <p>Un agent boucle — il relance une recherche à chaque reformulation, et {@code spectra_ask}
 * occupe le LLM plusieurs secondes par appel. Sans limite, un seul agent mal réglé suffit à
 * rendre le Playground inutilisable pour tout le monde.</p>
 *
 * <p><b>Un seau, pas un par appelant</b> : Spectra n'a qu'une clé ({@code SPECTRA_API_KEY}),
 * donc rien ne distingue deux agents. Indexer sur l'id de session MCP serait une fausse
 * précision — une reconnexion suffit à en changer et à repartir avec un seau plein. Le refus
 * dit combien attendre, pour qu'un agent puisse s'y conformer plutôt que réessayer aussitôt.</p>
 */
@Component
@ConditionalOnProperty(name = "spectra.mcp.enabled", havingValue = "true")
public class McpRateLimiter {

    private final int callsPerMinute;
    private final double capacity;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefill;

    // @Autowired désigne le constructeur à Spring : le second, à horloge injectable, sert aux tests.
    @Autowired
    public McpRateLimiter(@Value("${spectra.mcp.rate-limit.calls-per-minute:60}") int callsPerMinute,
                          @Value("${spectra.mcp.rate-limit.burst:10}") int burst) {
        this(callsPerMinute, burst, System::nanoTime);
    }

    McpRateLimiter(int callsPerMinute, int burst, LongSupplier nanoClock) {
        this.callsPerMinute = callsPerMinute;
        this.capacity = Math.max(1, burst);
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastRefill = nanoClock.getAsLong();
    }

    /** Consomme un jeton, ou lève {@link McpRateLimited} avec l'attente nécessaire. 0 désactive la limite. */
    public void acquire(String tool) {
        if (callsPerMinute <= 0) {
            return;
        }
        long waitMs;
        synchronized (this) {
            double perNano = callsPerMinute / 60e9;
            long now = nanoClock.getAsLong();
            tokens = Math.min(capacity, tokens + (now - lastRefill) * perNano);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return;
            }
            waitMs = (long) Math.ceil((1 - tokens) / perNano / 1e6);
        }
        throw new McpRateLimited(("%s refused: this server allows %d MCP calls per minute (burst %d). "
                + "Wait about %d ms before the next call; calling again sooner is refused the same way.")
                .formatted(tool, callsPerMinute, (int) capacity, waitMs), waitMs);
    }

    /** Refus pour débit : ni une erreur de l'agent, ni une panne de Spectra. */
    public static final class McpRateLimited extends RuntimeException {

        private final long retryAfterMs;

        McpRateLimited(String message, long retryAfterMs) {
            // Cause muette, comme McpToolFailure : Spring AI ajoute le message de la cause racine.
            super(message, McpToolFailure.Silent.INSTANCE, false, false);
            this.retryAfterMs = retryAfterMs;
        }

        public long retryAfterMs() {
            return retryAfterMs;
        }
    }
}
