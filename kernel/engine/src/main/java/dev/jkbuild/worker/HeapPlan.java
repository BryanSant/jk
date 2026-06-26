// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

/**
 * Turns "how much memory is free" + "how many worker JVMs we'd like to run at
 * once" into a concrete heap budget: a final concurrency and the per-JVM
 * {@code -Xms} / {@code -XX:SoftMaxHeapSize} / {@code -Xmx} sizes.
 *
 * <p>The policy (all sizes in bytes):
 * <ol>
 *   <li>reserve a buffer for the rest of the machine — {@code max(10%, 96 MiB)};</li>
 *   <li>{@code usable = available − buffer};</li>
 *   <li>shrink the requested JVM count while a JVM's share
 *       ({@code usable / count}) is below {@link #MIN_PARALLEL_HEAP} (512 MiB) —
 *       parallelism is only worth it if each fork gets a real heap;</li>
 *   <li>if it collapses to a single JVM and even that is under 512 MiB, run
 *       serial with {@code -Xmx = usable} and emit a {@linkplain Plan#warning()
 *       warning};</li>
 *   <li>otherwise {@code -Xmx = usable / count} (burst), the soft target is
 *       {@code clamp(50% × perJvm, 512 MiB, perJvm)} (good neighbour — ZGC
 *       floats up to it and {@code ZUncommit} returns the rest), and {@code -Xms}
 *       is small so nothing is pre-committed.</li>
 * </ol>
 */
public final class HeapPlan {

    private HeapPlan() {}

    static final long MIN_PARALLEL_HEAP = 512L << 20; // 512 MiB per JVM to bother parallelising
    static final long BUFFER_FLOOR = 96L << 20; // never reserve less than 96 MiB
    static final double BUFFER_FRACTION = 0.10; // ...or 10% of available, whichever is greater
    static final double SOFT_FRACTION = 0.50; // good-neighbour soft target
    static final long XMS_FLOOR = 64L << 20; // small initial heap; ZGC grows toward soft-max
    static final long XMX_FLOOR = 32L << 20; // never hand a JVM a degenerate heap

    /**
     * The resolved budget. {@code parallelism} is the number of worker JVMs that
     * may run at once; the heap sizes apply to each one. {@code warning} is
     * non-null only when forced to a sub-512 MiB serial best-effort run.
     */
    public record Plan(int parallelism, long xmsBytes, long softMaxBytes, long xmxBytes, String warning) {}

    /**
     * Peak worker-JVM count we'd like before the memory veto: {@code modules}
     * built at once, each forking up to {@code workers} test JVMs.
     * {@code --parallel-tests} lets test phases overlap (so the peak multiplies);
     * otherwise tests are gated to one module at a time and the peak is the
     * larger of the two. Clamped to {@code cap} (the usable thread count).
     */
    public static int requestedJvms(int modules, int workers, boolean parallelTests, int cap) {
        int m = Math.max(1, modules);
        int w = Math.max(1, workers);
        int peak = parallelTests ? m * w : Math.max(m, w);
        return Math.max(1, Math.min(peak, Math.max(1, cap)));
    }

    /** Compute the budget for {@code availableBytes} of free memory and {@code requestedJvms} desired forks. */
    public static Plan compute(long availableBytes, int requestedJvms) {
        long available = Math.max(0, availableBytes);
        long buffer = Math.max((long) (available * BUFFER_FRACTION), BUFFER_FLOOR);
        long usable = Math.max(0, available - buffer);

        int jvms = Math.max(1, requestedJvms);
        while (jvms > 1 && usable / jvms < MIN_PARALLEL_HEAP) jvms--;

        long perJvm = Math.max(XMX_FLOOR, usable / jvms);
        String warning = null;
        if (jvms == 1 && usable < MIN_PARALLEL_HEAP) {
            warning = "only " + mib(usable) + " MiB of usable memory (< 512 MiB) — " + "building serially with -Xmx"
                    + mib(perJvm) + "m as a best effort";
        }

        long softMax = Math.min(perJvm, Math.max((long) (perJvm * SOFT_FRACTION), MIN_PARALLEL_HEAP));
        long xms = Math.min(XMS_FLOOR, perJvm);
        return new Plan(jvms, xms, softMax, perJvm, warning);
    }

    /** Bytes → whole mebibytes (floored). */
    public static long mib(long bytes) {
        return bytes >> 20;
    }
}
