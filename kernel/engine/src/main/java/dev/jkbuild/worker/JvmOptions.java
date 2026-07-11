// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import dev.jkbuild.config.SessionContext;
import dev.jkbuild.config.WorkerTuning;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JVM tuning for the worker JVMs jk forks (compilers, test runners, etc.).
 *
 * <p>jk runs the build pipeline in the CLI process and forks <b>worker</b> JVMs for
 * compile/test/etc. Because {@code jk build --workers N} forks {@code N} test JVMs at once, a flat
 * "use 70% of RAM" would overcommit. The default here is a conservative {@link
 * #DEFAULT_MAX_RAM_PERCENT}; for a set of {@code N} concurrently-launched JVMs the cap is divided
 * by {@code N} (see {@link #flags(WorkerTuning, int)}), so the live workers fit.
 *
 * <p>Settings resolve highest-precedence-first: <b>CLI flag</b> ({@code --max-ram-percent} / {@code
 * --jvm-arg}) &gt; <b>env</b> ({@code JK_MAX_RAM_PERCENT}, {@code JK_JVM_GC}, {@code
 * JK_JVM_STRING_DEDUP}, {@code JK_JVM_ARGS}) &gt; <b>{@code [jvm]} in jk.toml</b> &gt; default.
 *
 * <p>The resolved {@link WorkerTuning} is carried on the request-scoped {@link
 * dev.jkbuild.config.Session} (installed by the CLI composition root) and read here via {@link
 * #tuning()} — so worker forks pick up the request's tuning instead of a process-global channel.
 */
public final class JvmOptions {

    private JvmOptions() {}

    /** Conservative default: a worker plus the resident CLI fit under it. */
    public static final double DEFAULT_MAX_RAM_PERCENT = 50.0;

    /** Default collector: low-pause, uncommits idle heap. */
    public static final String DEFAULT_GC = "zgc";

    /**
     * Metaspace lives outside the heap budget {@link HeapPlan} sizes, so an unbounded default lets
     * several concurrent worker JVMs overcommit native memory even when their heaps are well behaved.
     * Capped unless the caller already set one.
     */
    public static final long DEFAULT_MAX_METASPACE_MB = 256;

    /** Worker JVMs (compilers, test runners) rarely need deep stacks; smaller reserve, more headroom. */
    public static final long DEFAULT_STACK_KB = 512;

    /**
     * Build the JVM flag list for {@code settings}, dividing the heap cap across {@code concurrency}
     * simultaneously-launched JVMs (pass {@code 1} for a lone worker; the test-runner passes its
     * worker count).
     */
    public static List<String> flags(WorkerTuning settings, int concurrency) {
        WorkerTuning s = settings == null ? WorkerTuning.NONE : settings;
        double base = s.maxRamPercent() != null ? s.maxRamPercent() : DEFAULT_MAX_RAM_PERCENT;
        double perJvm = base / Math.max(1, concurrency);
        String gc = (s.gc() != null ? s.gc() : DEFAULT_GC).toLowerCase(Locale.ROOT);
        boolean dedup = s.stringDedup() == null || s.stringDedup();

        List<String> out = new ArrayList<>();
        out.add("-XX:MaxRAMPercentage=" + fmt(perJvm));
        switch (gc) {
            case "zgc" -> out.add("-XX:+UseZGC");
            case "g1" -> out.add("-XX:+UseG1GC");
            case "none", "default", "" -> {
                /* leave the JVM's own default */
            }
            default -> out.add("-XX:+UseZGC");
        }
        // String deduplication only has an effect on G1/ZGC; skip it otherwise.
        if (dedup && (gc.equals("zgc") || gc.equals("g1"))) {
            out.add("-XX:+UseStringDeduplication");
        }
        addHardening(out, s, concurrency);
        out.addAll(s.extraArgs());
        return out;
    }

    /**
     * The request-scoped worker tuning, read from the current {@link dev.jkbuild.config.Session}. When
     * the session carries none (e.g. a direct engine/test call that bypassed the CLI composition
     * root), fall back to the {@code JK_*} env layer — mirroring the old {@code processSettings()}
     * default.
     */
    private static WorkerTuning tuning() {
        var session = SessionContext.current();
        WorkerTuning t = session.jvm();
        WorkerTuning base =
                (t == null || t == WorkerTuning.NONE) ? dev.jkbuild.config.WorkerTunings.fromEnv() : t;
        // The jk.toml [jvm] table overlays here, at fork time, engine-side (thin-client contract):
        // the session carries only the client's flag/env layers, so a client of any age gets
        // current-engine [jvm] interpretation.
        return dev.jkbuild.config.WorkerTunings.overlayProject(base, session.workingDir());
    }

    /**
     * Worker-fork JVM flags for {@code concurrency} simultaneously-launched JVMs, built from the
     * request's {@linkplain #tuning() tuning} — so a {@code --max-ram-percent} flag or a {@code [jvm]}
     * table reaches the worker.
     *
     * <p>When a {@linkplain #processHeapPlan() heap plan} is in effect (the default — no explicit
     * heap tuning), absolute {@code -Xms}/{@code -Xmx}/ {@code -XX:SoftMaxHeapSize} from the plan
     * replace the relative {@code MaxRAMPercentage}; the plan already accounts for how many JVMs run
     * at once, so {@code concurrency} is ignored in that case.
     */
    public static List<String> workerFlags(int concurrency) {
        WorkerTuning s = tuning();
        HeapPlan.Plan plan = processHeapPlan();
        if (plan != null && autoHeapEnabled(s)) return absoluteFlags(plan, s);
        return flags(s, concurrency);
    }

    /**
     * Resolved heap budget for this invocation, or {@code null} when unset / explicitly overridden.
     *
     * <p>Unlike JVM tuning (now request-scoped on {@link dev.jkbuild.config.Session}), the heap plan
     * and its paired {@link WorkerSlots} permit count remain per-invocation resource-management state
     * configured once by the CLI ({@link #planAndApply}). Making them per-session pools is a later
     * server-hardening step (see docs/architecture/re-foundation.md, M1c remainder).
     */
    private static volatile HeapPlan.Plan heapPlan;

    /**
     * Probe memory, compute the heap budget for {@code requestedJvms} desired forks, and apply it:
     * stash it for {@link #workerFlags} and size {@link WorkerSlots} so no more than the plan's
     * parallelism run at once. A no-op (returns {@code null}, opens the worker gate) when the request
     * supplied explicit heap tuning — those settings then drive sizing as before.
     */
    public static HeapPlan.Plan planAndApply(int requestedJvms) {
        if (!autoHeapEnabled(tuning())) {
            WorkerSlots.configure(0); // unbounded: honour the user's relative/explicit sizing
            heapPlan = null;
            return null;
        }
        HeapPlan.Plan plan = HeapPlan.compute(MemoryProbe.probe().availableBytes(), requestedJvms);
        heapPlan = plan;
        WorkerSlots.configure(plan.parallelism());
        return plan;
    }

    /** The applied heap budget, or {@code null} if none (explicit tuning / not yet planned). */
    public static HeapPlan.Plan processHeapPlan() {
        return heapPlan;
    }

    /**
     * Test-only: undo {@link #planAndApply} — clears the shared heap plan and reopens the {@link
     * WorkerSlots} gate. Production code never calls this (a real process's plan is meant to live for
     * the process's whole lifetime); it exists because a test that spins up a real {@code
     * EngineServer} (which calls {@code planAndApply} as a side effect of starting) would otherwise
     * leak that process-wide static into unrelated tests sharing the same test JVM.
     */
    public static void resetSharedPlanForTests() {
        heapPlan = null;
        WorkerSlots.configure(0);
    }

    /**
     * True when jk should auto-size worker heaps: the request pinned neither a {@code
     * --max-ram-percent} / {@code [jvm] max-ram-percent} nor an explicit heap flag ({@code
     * -Xmx}/{@code -Xms}/{@code -XX:MaxHeapSize}/ {@code -XX:MaxRAMPercentage}) via {@code --jvm-arg}
     * / {@code [jvm] args}.
     */
    public static boolean autoHeapEnabled() {
        return autoHeapEnabled(tuning());
    }

    private static boolean autoHeapEnabled(WorkerTuning s) {
        if (s.maxRamPercent() != null) return false;
        for (String a : s.extraArgs()) {
            if (a.startsWith("-Xmx")
                    || a.startsWith("-Xms")
                    || a.startsWith("-XX:MaxHeapSize")
                    || a.startsWith("-XX:MinHeapSize")
                    || a.startsWith("-XX:MaxRAMPercentage")
                    || a.startsWith("-XX:SoftMaxHeapSize")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Absolute-heap worker flags from {@code plan}: small {@code -Xms}, a {@code SoftMaxHeapSize}
     * good-neighbour target, an {@code -Xmx} burst cap, and the collector (default generational ZGC
     * with {@code ZUncommit} so idle heap is returned to the OS). {@code SoftMaxHeapSize} is only
     * emitted for collectors that honour it (ZGC / G1).
     */
    static List<String> absoluteFlags(HeapPlan.Plan plan, WorkerTuning s) {
        String gc = (s.gc() != null ? s.gc() : DEFAULT_GC).toLowerCase(Locale.ROOT);
        boolean dedup = s.stringDedup() == null || s.stringDedup();
        boolean softMaxAware = gc.equals("zgc") || gc.equals("g1");

        List<String> out = new ArrayList<>();
        out.add("-Xms" + HeapPlan.mib(plan.xmsBytes()) + "m");
        out.add("-Xmx" + HeapPlan.mib(plan.xmxBytes()) + "m");
        if (softMaxAware) out.add("-XX:SoftMaxHeapSize=" + HeapPlan.mib(plan.softMaxBytes()) + "m");
        switch (gc) {
            case "zgc" -> {
                out.add("-XX:+UseZGC");
                out.add("-XX:+ZUncommit");
                out.add("-XX:ZUncommitDelay=" + ZGC_UNCOMMIT_DELAY_SECONDS);
            }
            case "g1" -> out.add("-XX:+UseG1GC");
            case "none", "default", "" -> {
                /* JVM default collector */
            }
            default -> {
                out.add("-XX:+UseZGC");
                out.add("-XX:+ZUncommit");
                out.add("-XX:ZUncommitDelay=" + ZGC_UNCOMMIT_DELAY_SECONDS);
            }
        }
        if (dedup && (gc.equals("zgc") || gc.equals("g1"))) out.add("-XX:+UseStringDeduplication");
        addHardening(out, s, plan.parallelism());
        out.addAll(s.extraArgs());
        return out;
    }

    /** How long ZGC waits on an idle heap before returning pages to the OS — tuned for aggressive give-back. */
    static final int ZGC_UNCOMMIT_DELAY_SECONDS = 10;

    /**
     * Native-memory and fail-fast hardening applied to every worker JVM (relative or absolute heap
     * mode alike), skipping anything the caller already pinned via {@code --jvm-arg} / {@code [jvm]
     * args}:
     *
     * <ul>
     *   <li>{@code -XX:MaxMetaspaceSize} — bounds native class-metadata memory, which lives outside
     *       the heap budget {@link HeapPlan} sizes.
     *   <li>{@code -XX:ActiveProcessorCount} — scaled by {@code concurrency} so each simultaneously
     *       forked JVM sizes its own GC/JIT thread pools (and their native memory) for its actual CPU
     *       share instead of the whole host's core count.
     *   <li>{@code -Xss} — a smaller thread-stack reserve; worker JVMs rarely need the JVM default.
     *   <li>{@code -XX:+ExitOnOutOfMemoryError} — die immediately on OOM rather than degrade in place,
     *       so the parent sees a failed fork instead of a thrashing process still holding a slot.
     * </ul>
     */
    private static void addHardening(List<String> out, WorkerTuning s, int concurrency) {
        List<String> extra = s.extraArgs();
        if (!hasArgPrefix(extra, "-XX:MaxMetaspaceSize", "-XX:MetaspaceSize")) {
            out.add("-XX:MaxMetaspaceSize=" + DEFAULT_MAX_METASPACE_MB + "m");
        }
        if (!hasArgPrefix(extra, "-XX:ActiveProcessorCount")) {
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors() / Math.max(1, concurrency));
            out.add("-XX:ActiveProcessorCount=" + cores);
        }
        if (!hasArgPrefix(extra, "-Xss")) {
            out.add("-Xss" + DEFAULT_STACK_KB + "k");
        }
        if (!hasArgPrefix(extra, "-XX:+ExitOnOutOfMemoryError", "-XX:-ExitOnOutOfMemoryError", "-XX:+CrashOnOutOfMemoryError")) {
            out.add("-XX:+ExitOnOutOfMemoryError");
        }
    }

    private static boolean hasArgPrefix(List<String> args, String... prefixes) {
        for (String a : args) {
            for (String p : prefixes) {
                if (a.startsWith(p)) return true;
            }
        }
        return false;
    }

    /**
     * {@code workerFlags}, but each flag {@code -J}-prefixed for launcher tools that wrap their own
     * JVM (javac, native-image) rather than being exec'd as {@code java} directly.
     */
    public static List<String> launcherFlags(int concurrency) {
        List<String> out = new ArrayList<>();
        for (String f : workerFlags(concurrency)) out.add("-J" + f);
        return out;
    }

    /**
     * Assemble a worker JVM command line: {@code javaExe}, then the tuning flags ({@link
     * #workerFlags}), then {@code rest} (e.g. {@code -cp <jar> Main <spec>}). For forks not driven by
     * {@link dev.jkbuild.worker.PluginLoader} — the compiler/git workers and the CLI's standalone
     * worker commands.
     */
    public static List<String> javaCommand(String javaExe, int concurrency, List<String> rest) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.addAll(workerFlags(concurrency));
        cmd.addAll(rest);
        return cmd;
    }

    // ---- helpers --------------------------------------------------------

    /** Whole numbers render without a trailing {@code .0} ({@code 50}, not {@code 50.0}). */
    private static String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
