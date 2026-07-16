// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.util.concurrent.Semaphore;

/**
 * A process-wide cap on how many worker JVMs run concurrently. jk forks all workers — compilers,
 * the test runner, git, image/format/etc. — through {@link PluginProcess}, which takes a
 * {@linkplain #acquire() lease} for the lifetime of each child process. The CLI sizes the permit
 * count from the memory plan (see {@code HeapPlan}) so that the per-JVM heap times the number of
 * simultaneously-live JVMs stays within the machine's usable memory.
 *
 * <p>Unconfigured (or configured with a non-positive count) the gate is open — every fork proceeds
 * immediately, preserving the pre-memory-plan behavior for code paths and tests that never call
 * {@link #configure}.
 */
public final class PluginSlots {

    private PluginSlots() {}

    /** {@code null} ⇒ unbounded (open gate). */
    private static volatile Semaphore slots;

    /** Bound concurrent worker JVMs to {@code permits}; {@code permits <= 0} reopens the gate. */
    public static void configure(int permits) {
        slots = permits > 0 ? new Semaphore(permits, /* fair= */ true) : null;
    }

    /** Current permit count, or {@code 0} when unbounded. Diagnostics only. */
    public static int permits() {
        Semaphore s = slots;
        return s == null ? 0 : s.availablePermits();
    }

    /**
     * Reserve one slot, blocking until one is free; release it via {@link Lease#close()}
     * (try-with-resources). A no-op lease when unbounded or if the wait is interrupted (the fork
     * proceeds rather than stalling).
     */
    public static Lease acquire() {
        Semaphore s = slots;
        if (s == null) return () -> {};
        try {
            s.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return () -> {};
        }
        return s::release;
    }

    /** A held slot; closing returns it to the pool. */
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
