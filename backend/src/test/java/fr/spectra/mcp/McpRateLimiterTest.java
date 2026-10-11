package fr.spectra.mcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class McpRateLimiterTest {

    private final AtomicLong now = new AtomicLong();

    @Test
    void theBurstIsServedThenTheNextCallIsRefusedWithTheWait() {
        McpRateLimiter limiter = new McpRateLimiter(60, 2, now::get);

        limiter.acquire("spectra_search");
        limiter.acquire("spectra_search");

        assertThatThrownBy(() -> limiter.acquire("spectra_search"))
                .isInstanceOf(McpRateLimiter.McpRateLimited.class)
                .hasMessageContaining("spectra_search refused")
                .hasMessageContaining("60 MCP calls per minute (burst 2)")
                .hasMessageContaining("Wait about 1000 ms")
                .satisfies(e -> {
                    assertThat(((McpRateLimiter.McpRateLimited) e).retryAfterMs()).isEqualTo(1000);
                    assertThat(e.getCause().getMessage()).isEmpty();
                });
    }

    @Test
    void waitingAsLongAsTheRefusalSaidIsEnough() {
        McpRateLimiter limiter = new McpRateLimiter(60, 1, now::get);
        limiter.acquire("t");

        now.addAndGet(1_000_000_000L);

        assertThatNoException().isThrownBy(() -> limiter.acquire("t"));
    }

    @Test
    void zeroCallsPerMinuteTurnsTheLimitOff() {
        McpRateLimiter limiter = new McpRateLimiter(0, 1, now::get);

        for (int i = 0; i < 1_000; i++) {
            limiter.acquire("t");
        }
    }

    @Test
    void aRefusedCallIsJournaledAsRateLimitedAndNeverRuns() {
        McpCallJournal journal = new McpCallJournal(new SimpleMeterRegistry(), new McpRateLimiter(60, 1, now::get));
        AtomicLong runs = new AtomicLong();

        journal.record("t", Map.of(), runs::incrementAndGet);
        assertThatThrownBy(() -> journal.record("t", Map.of(), runs::incrementAndGet))
                .isInstanceOf(McpRateLimiter.McpRateLimited.class);

        assertThat(runs.get()).isEqualTo(1);
        assertThat(journal.recent(1).getFirst().outcome()).isEqualTo(McpCallJournal.Outcome.RATE_LIMITED);
    }
}
