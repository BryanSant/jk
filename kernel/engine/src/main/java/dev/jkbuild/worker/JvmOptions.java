// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import dev.jkbuild.config.EnvValues;
import dev.jkbuild.config.TomlValues;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * JVM tuning for the worker JVMs jk forks (compilers, test runners, etc.).
 *
 * <p>jk runs the build pipeline in the CLI process and forks <b>worker</b> JVMs
 * for compile/test/etc. Because {@code jk build --workers N} forks {@code N} test
 * JVMs at once, a flat "use 70% of RAM" would overcommit. The default here is a
 * conservative {@link #DEFAULT_MAX_RAM_PERCENT}; for a set of {@code N}
 * concurrently-launched JVMs the cap is divided by {@code N}
 * (see {@link #flags(Settings, int)}), so the live workers fit.
 *
 * <p>Settings resolve highest-precedence-first: <b>CLI flag</b>
 * ({@code --max-ram-percent} / {@code --jvm-arg}) &gt; <b>env</b>
 * ({@code JK_MAX_RAM_PERCENT}, {@code JK_JVM_GC}, {@code JK_JVM_STRING_DEDUP},
 * {@code JK_JVM_ARGS}) &gt; <b>{@code [jvm]} in jk.toml</b> &gt; default.
 */
public final class JvmOptions {

    private JvmOptions() {}

    /** Conservative default: a worker plus the resident CLI fit under it. */
    public static final double DEFAULT_MAX_RAM_PERCENT = 50.0;
    /** Default collector: low-pause, uncommits idle heap. */
    public static final String DEFAULT_GC = "zgc";

    public static final String ENV_MAX_RAM = "JK_MAX_RAM_PERCENT";
    public static final String ENV_GC = "JK_JVM_GC";
    public static final String ENV_STRING_DEDUP = "JK_JVM_STRING_DEDUP";
    public static final String ENV_ARGS = "JK_JVM_ARGS";

    /**
     * Resolved (or partially-resolved) tuning. {@code null} scalars mean "fall
     * back to the next layer / the built-in default". {@code extraArgs} are raw
     * flags appended verbatim.
     */
    public record Settings(Double maxRamPercent, String gc, Boolean stringDedup, List<String> extraArgs) {
        public Settings {
            extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        }
        /** The empty layer — every field unset. */
        public static final Settings NONE = new Settings(null, null, null, List.of());
    }

    /** Overlay {@code high} onto {@code low}: high's non-null scalars win; args concatenate (low first). */
    private static Settings overlay(Settings high, Settings low) {
        List<String> args = new ArrayList<>(low.extraArgs());
        args.addAll(high.extraArgs());
        return new Settings(
                high.maxRamPercent() != null ? high.maxRamPercent() : low.maxRamPercent(),
                high.gc() != null ? high.gc() : low.gc(),
                high.stringDedup() != null ? high.stringDedup() : low.stringDedup(),
                args);
    }

    /** Resolve precedence: {@code cli} &gt; env &gt; {@code projectDir/jk.toml [jvm]} &gt; default. */
    public static Settings resolve(Settings cli, Path projectDir) {
        Settings eff = cli == null ? Settings.NONE : cli;
        eff = overlay(eff, fromEnv());
        if (projectDir != null) eff = overlay(eff, fromToml(projectDir.resolve("jk.toml")));
        return eff;
    }

    /** The {@code JK_*} environment layer. Coercion via the shared {@link EnvValues}. */
    public static Settings fromEnv() {
        return new Settings(
                EnvValues.doubleValue(System::getenv, ENV_MAX_RAM).orElse(null),
                EnvValues.string(System::getenv, ENV_GC).orElse(null),
                EnvValues.bool(System::getenv, ENV_STRING_DEDUP).orElse(null),
                splitArgs(System.getenv(ENV_ARGS)));
    }

    /**
     * The {@code [jvm]} table of a {@code jk.toml}, or {@link Settings#NONE}.
     * Never throws — a missing/malformed file or table degrades to {@code NONE}.
     * Coercion via the shared {@link TomlValues} ({@code max-ram-percent} accepts
     * a TOML integer or float; {@code args} keeps only string elements).
     */
    public static Settings fromToml(Path jkToml) {
        Optional<TomlParseResult> parsed = TomlValues.parse(jkToml);
        if (parsed.isEmpty()) return Settings.NONE;
        TomlTable jvm = parsed.get().getTable("jvm");
        if (jvm == null) return Settings.NONE;
        return new Settings(
                TomlValues.optDouble(jvm, "max-ram-percent").orElse(null),
                TomlValues.optString(jvm, "gc").orElse(null),
                TomlValues.optBoolean(jvm, "string-dedup").orElse(null),
                TomlValues.stringList(jvm, "args"));
    }

    /**
     * Build the JVM flag list for {@code settings}, dividing the heap cap across
     * {@code concurrency} simultaneously-launched JVMs (pass {@code 1} for a
     * lone worker; the test-runner passes its worker count).
     */
    public static List<String> flags(Settings settings, int concurrency) {
        Settings s = settings == null ? Settings.NONE : settings;
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
        out.addAll(s.extraArgs());
        return out;
    }

    /**
     * Process-wide resolved tuning for this jk invocation. The CLI resolves it
     * once (flag &gt; env &gt; jk.toml &gt; default) via {@link #setProcessSettings}
     * and every worker fork reads it through {@link #workerFlags}. Since jk runs
     * the pipeline and forks workers all in one process, a process-global is the
     * propagation channel that the now-removed host JVM used to provide.
     */
    private static volatile Settings processSettings;

    /** Stash the fully-resolved tuning so every subsequent worker fork picks it up. */
    public static void setProcessSettings(Settings settings) {
        processSettings = settings;
    }

    /** The resolved process tuning, or the env layer when unset (e.g. direct test calls). */
    public static Settings processSettings() {
        Settings s = processSettings;
        return s != null ? s : fromEnv();
    }

    /**
     * Worker-fork JVM flags for {@code concurrency} simultaneously-launched JVMs,
     * built from the resolved {@linkplain #processSettings() process tuning} — so a
     * {@code --max-ram-percent} flag or a {@code [jvm]} table reaches the worker.
     *
     * <p>When a {@linkplain #processHeapPlan() heap plan} is in effect (the
     * default — no explicit heap tuning), absolute {@code -Xms}/{@code -Xmx}/
     * {@code -XX:SoftMaxHeapSize} from the plan replace the relative
     * {@code MaxRAMPercentage}; the plan already accounts for how many JVMs run
     * at once, so {@code concurrency} is ignored in that case.
     */
    public static List<String> workerFlags(int concurrency) {
        HeapPlan.Plan plan = processHeapPlan();
        if (plan != null && autoHeapEnabled()) return absoluteFlags(plan, processSettings());
        return flags(processSettings(), concurrency);
    }

    /** Resolved heap budget for this invocation, or {@code null} when unset / explicitly overridden. */
    private static volatile HeapPlan.Plan heapPlan;

    /**
     * Probe memory, compute the heap budget for {@code requestedJvms} desired
     * forks, and apply it process-wide: stash it for {@link #workerFlags} and
     * size {@link WorkerSlots} so no more than the plan's parallelism run at
     * once. A no-op (returns {@code null}, opens the worker gate) when the user
     * supplied explicit heap tuning — their settings then drive sizing as before.
     */
    public static HeapPlan.Plan planAndApply(int requestedJvms) {
        if (!autoHeapEnabled()) {
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
     * True when jk should auto-size worker heaps: the user pinned neither a
     * {@code --max-ram-percent} / {@code [jvm] max-ram-percent} nor an explicit
     * heap flag ({@code -Xmx}/{@code -Xms}/{@code -XX:MaxHeapSize}/
     * {@code -XX:MaxRAMPercentage}) via {@code --jvm-arg} / {@code [jvm] args}.
     */
    public static boolean autoHeapEnabled() {
        Settings s = processSettings();
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
     * Absolute-heap worker flags from {@code plan}: small {@code -Xms}, a
     * {@code SoftMaxHeapSize} good-neighbour target, an {@code -Xmx} burst cap,
     * and the collector (default generational ZGC with {@code ZUncommit} so idle
     * heap is returned to the OS). {@code SoftMaxHeapSize} is only emitted for
     * collectors that honour it (ZGC / G1).
     */
    static List<String> absoluteFlags(HeapPlan.Plan plan, Settings s) {
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
                out.add("-XX:ZUncommitDelay=30");
            }
            case "g1" -> out.add("-XX:+UseG1GC");
            case "none", "default", "" -> {
                /* JVM default collector */
            }
            default -> {
                out.add("-XX:+UseZGC");
                out.add("-XX:+ZUncommit");
                out.add("-XX:ZUncommitDelay=30");
            }
        }
        if (dedup && (gc.equals("zgc") || gc.equals("g1"))) out.add("-XX:+UseStringDeduplication");
        out.addAll(s.extraArgs());
        return out;
    }

    /**
     * Assemble a worker JVM command line: {@code javaExe}, then the tuning flags
     * ({@link #workerFlags}), then {@code rest} (e.g. {@code -cp <jar> Main <spec>}).
     * For forks not driven by {@link dev.jkbuild.worker.PluginLoader} — the
     * compiler/git workers and the CLI's standalone worker commands.
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

    private static List<String> splitArgs(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : s.trim().split("\\s+")) if (!part.isBlank()) out.add(part);
        return out;
    }
}
