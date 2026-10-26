package fr.spectra.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionMaintenanceGuardTest {
    @TempDir Path directory;

    @Test
    void sameModelMigrationRemainsBlockedAfterRestartUntilFullCompletion() {
        var guard = new CollectionMaintenanceGuard(directory);
        guard.register("docs", "id");
        try (var ignored = guard.acquire("docs")) { }
        guard.begin("docs", "id");
        assertThatThrownBy(() -> guard.acquire("docs")).isInstanceOf(IllegalStateException.class);
        var restarted = new CollectionMaintenanceGuard(directory);
        assertThatThrownBy(() -> restarted.acquireId("id")).isInstanceOf(IllegalStateException.class);
        // A failed job does not complete; a full rerun can reuse the durable barrier.
        restarted.begin("docs", "id");
        assertThatThrownBy(() -> restarted.acquire("docs")).isInstanceOf(IllegalStateException.class);
        restarted.complete("docs");
        try (var ignored = restarted.acquireId("id")) { }
    }

    @Test
    void beginWaitsForAlreadyAdmittedPipeline() throws Exception {
        var guard = new CollectionMaintenanceGuard(directory);
        var admitted = guard.acquire("docs");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            entered.countDown();
            guard.begin("docs", "id");
            finished.countDown();
        });
        worker.start();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(50, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            admitted.close();
            worker.join(2000);
        }
        assertThat(finished.getCount()).isZero();
        assertThatThrownBy(() -> guard.acquire("docs")).isInstanceOf(IllegalStateException.class);
    }
    @Test
    void queuedMigrationDoesNotDeadlockHybridWorkerWithParentLease() throws Exception {
        var guard = new CollectionMaintenanceGuard(directory);
        var parent = guard.acquire("docs");
        var writer = new Thread(() -> guard.begin("docs", "id"));
        writer.start();
        Thread child = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (writer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(writer.getState()).isEqualTo(Thread.State.WAITING);
            var childCompleted = new CountDownLatch(1);
            child = new Thread(() -> {
                try (var ignored = guard.acquire("docs")) { childCompleted.countDown(); }
            });
            child.start();
            assertThat(childCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            parent.close();
            if (child != null) child.join(2000);
            writer.join(2000);
        }
        assertThat(writer.isAlive()).isFalse();
    }
}
