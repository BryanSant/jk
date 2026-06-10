// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared executor pools for the whole CLI. Lazily created on first use,
 * shut down via a single shutdown hook so the JVM can still exit even if
 * a caller forgets to clean up.
 *
 * <ul>
 *   <li>{@link #cpu()} — bounded {@link ForkJoinPool} sized to
 *       {@code min(coreCount, 8)}. For hashing, decompression, parallel
 *       per-file work — anything CPU-bound.</li>
 *   <li>{@link #io()} — unbounded virtual-thread executor. For HTTP
 *       fetches, filesystem walks, probe-chain enumeration — anything
 *       that mostly blocks on syscalls or the network.</li>
 * </ul>
 *
 * <p>{@link dev.jkbuild.cli.tui.GlobalCancel} ultimately calls
 * {@link Runtime#halt} for Ctrl-C, which kills all threads in either
 * pool immediately. No graceful cancellation hook is required.
 */
public final class JkThreads {

    /** Parallelism for the CPU pool — clamped so we don't over-subscribe small machines via 96-thread containers. */
    public static final int CPU_THREADS = Math.min(Runtime.getRuntime().availableProcessors(), 8);

    private static volatile ExecutorService cpu;
    private static volatile ExecutorService io;
    private static final Object LOCK = new Object();

    private JkThreads() {}

    /** Bounded CPU-bound pool. Threads are daemon, named {@code jk-cpu-N}. */
    public static ExecutorService cpu() {
        ExecutorService local = cpu;
        if (local != null) return local;
        synchronized (LOCK) {
            if (cpu == null) {
                cpu = newCpuPool();
                registerShutdownHook();
            }
            return cpu;
        }
    }

    /** Virtual-thread executor. One thread per task; names start with {@code jk-io-}. */
    public static ExecutorService io() {
        ExecutorService local = io;
        if (local != null) return local;
        synchronized (LOCK) {
            if (io == null) {
                io = Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("jk-io-", 0).factory());
                registerShutdownHook();
            }
            return io;
        }
    }

    private static ExecutorService newCpuPool() {
        AtomicInteger counter = new AtomicInteger();
        ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
            ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            t.setName("jk-cpu-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ForkJoinPool(CPU_THREADS, factory, null, /*asyncMode=*/ false);
    }

    private static volatile boolean hookRegistered = false;

    private static void registerShutdownHook() {
        if (hookRegistered) return;
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(JkThreads::shutdown, "jk-threads-shutdown"));
    }

    private static void shutdown() {
        // Best-effort: virtual threads + daemon CPU workers will die with the JVM,
        // but politely shutting down lets in-flight tasks unblock first.
        ExecutorService localCpu = cpu;
        if (localCpu != null) localCpu.shutdown();
        ExecutorService localIo = io;
        if (localIo != null) localIo.shutdown();
    }
}
