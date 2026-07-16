// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared executor pools for the whole CLI. Lazily created on first use, shut down via a single
 * shutdown hook so the JVM can still exit even if a caller forgets to clean up.
 *
 * <ul>
 *   <li>{@link #cpu()} — bounded {@link ForkJoinPool} sized to {@code min(coreCount, 8)}. For
 *       hashing, decompression, parallel per-file work — anything CPU-bound.
 *   <li>{@link #io()} — unbounded virtual-thread executor. For HTTP fetches, filesystem walks,
 *       probe-chain enumeration — anything that mostly blocks on syscalls or the network.
 * </ul>
 *
 * <p>The CLI's Ctrl-C handler ultimately calls {@link Runtime#halt}, which kills all threads in
 * either pool immediately. No graceful cancellation hook is required.
 *
 * <p>Both pools are returned wrapped in a {@link ContextPropagatingExecutorService}, so a
 * {@code where()}-bound request session (installed via the {@link ContextPropagator} seam) is captured
 * on the submitting thread and re-established on the worker thread. Until a propagator is bound the
 * wrapping is identity and behavior is unchanged. The wrapped instance is cached alongside the lazy
 * real pool so each pool is decorated exactly once.
 */
public final class JkThreads {

    /**
     * Parallelism for the CPU pool — clamped so we don't over-subscribe small machines via 96-thread
     * containers.
     */
    public static final int CPU_THREADS = Math.min(Runtime.getRuntime().availableProcessors(), 8);

    /** The real pools; {@link #cpu}/{@link #io} hold their context-propagating wrappers. */
    private static volatile ExecutorService cpuReal;

    private static volatile ExecutorService ioReal;
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
                cpuReal = newCpuPool();
                cpu = new ContextPropagatingExecutorService(cpuReal);
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
                ioReal = Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("jk-io-", 0).factory());
                io = new ContextPropagatingExecutorService(ioReal);
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
        return new ForkJoinPool(CPU_THREADS, factory, null, /* asyncMode= */ false);
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
        ExecutorService localCpu = cpuReal;
        if (localCpu != null) localCpu.shutdown();
        ExecutorService localIo = ioReal;
        if (localIo != null) localIo.shutdown();
    }
}
