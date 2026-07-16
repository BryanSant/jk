// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.jdk.GlobalDefaultJdk;
import cc.jumpkick.jdk.JdkLts;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkResolution;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.model.JkVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * A one-time, machine-scoped measurement of how fast <em>this</em> host runs jk's work, so a build
 * with no learned history still gets a wall-clock estimate anchored to the real hardware/OS
 * instead of a hardcoded constant.
 *
 * <p>The build's progress model counts abstract <em>weight</em> units and converts them to time
 * with {@link EffortWeights#MS_PER_WEIGHT} (≈150 ms/unit). That constant reflects one reference
 * machine. Once anything has been built, {@link StepTimings} has learned real per-unit rates and
 * the constant cancels out — so this calibration only governs the narrow first-ever-build window
 * on a fresh {@code ~/.jk}. There, we probe the host once (fork the JDK's {@code java}/{@code
 * javac}) and derive a measured {@code ms-per-weight} that replaces the constant for the cold
 * estimate. {@link #refine} then folds every subsequent real build's throughput back in (EWMA), so
 * the anchor tracks the machine over time.
 *
 * <p>Stored as TOML at {@code ~/.jk/state/builds/calibration.toml} (state, not cache — it describes
 * the machine and must survive {@code jk clean}; it lives alongside the build-history journal). A
 * file left at the legacy {@code ~/.jk/state/calibration.toml} is migrated on first read. Reads are
 * memoized per process. A probe or IO failure is always swallowed: calibration is advisory and must
 * never fail a build or an explain.
 */
public final class Calibration {

    /** EWMA recency for {@link #refine} — matches {@link StepTimings#DEFAULT_ALPHA}. */
    private static final double ALPHA = 0.4;

    /** Re-probe once a stored calibration is older than this (hardware/VM may have changed). */
    private static final long MAX_AGE_MILLIS = 60L * 86_400_000L; // ~60 days

    /** How many {@code java -version} forks to time (the first is discarded as a cold-cache warmup). */
    private static final int FORK_SAMPLES = 4;

    /** Sources in the synthetic javac probe — matches the divisor in {@link #deriveMsPerWeight}. */
    private static final int PROBE_SOURCES = 5;

    private static final AtomicReference<Calibration> MEMO = new AtomicReference<>();

    private final double msPerWeight;
    private final long jvmForkMs;
    private final long javacMs;
    private final double loadAtCalibration;
    private final int cores;
    private final String jdk;
    private final String jkVersion;
    private final long updated;
    /** False when the anchor is still the rough synthetic probe; true once a real build refined it. */
    private final boolean measured;

    private Calibration(
            double msPerWeight,
            long jvmForkMs,
            long javacMs,
            double loadAtCalibration,
            int cores,
            String jdk,
            String jkVersion,
            long updated,
            boolean measured) {
        this.msPerWeight = msPerWeight;
        this.jvmForkMs = jvmForkMs;
        this.javacMs = javacMs;
        this.loadAtCalibration = loadAtCalibration;
        this.cores = cores;
        this.jdk = jdk;
        this.jkVersion = jkVersion;
        this.updated = updated;
        this.measured = measured;
    }

    /**
     * The effective cold-estimate anchor: the measured {@code ms-per-weight} when a fresh, valid
     * calibration exists, else {@link EffortWeights#MS_PER_WEIGHT} so callers are always safe.
     */
    public double msPerWeight() {
        return msPerWeight > 0 ? msPerWeight : EffortWeights.MS_PER_WEIGHT;
    }

    /** True when this instance carries a real stored/probed measurement (vs. the safe fallback). */
    public boolean present() {
        return msPerWeight > 0;
    }

    /** False while the anchor is still the rough synthetic probe; true once a real build refined it. */
    public boolean measured() {
        return measured;
    }

    public double loadAtCalibration() {
        return loadAtCalibration;
    }

    /** Test seam: construct an instance directly (bypasses the probe/IO). */
    static Calibration testInstance(double msPerWeight, boolean measured, String version, long updated) {
        return new Calibration(msPerWeight, 10, 20, 1.5, 8, "jdk-x", version, updated, measured);
    }

    // --- load / ensure -------------------------------------------------------

    /** The calibration file: {@code ~/.jk/state/builds/calibration.toml}. */
    static Path file() {
        return JkDirs.builds().resolve("calibration.toml");
    }

    /**
     * Memoized read of the stored calibration. Returns the safe fallback (an instance whose {@link
     * #present()} is false) when the file is missing, unreadable, or stale — so a caller can always
     * use {@link #msPerWeight()} without a null check. Use {@link #ensure} to probe-if-absent.
     */
    public static Calibration load() {
        Calibration cached = MEMO.get();
        if (cached != null) return cached;
        Calibration read = readOrAbsent();
        MEMO.set(read);
        return read;
    }

    /**
     * Return a usable calibration, running the one-time host probe when none is stored or the stored
     * one is stale. This is the (sanctioned) exception to {@code jk explain} being a pure dry run:
     * the very first explain/build on a fresh {@code ~/.jk} pays a ~1–2 s probe once, then every
     * later run just reads the file. {@code jdksDir} mirrors the build's {@code --jdks-dir} override
     * ({@code null} = the normal JDK probe chain). Never throws.
     */
    public static Calibration ensure(Path jdksDir) {
        Calibration current = load();
        if (current.present()) return current;
        Calibration probed = probe(jdksDir);
        if (probed != null && probed.present()) {
            persist(probed);
            MEMO.set(probed);
            return probed;
        }
        return current; // absent → callers fall back to MS_PER_WEIGHT
    }

    /**
     * Fold a completed build's measured throughput ({@code observedMsPerWeight}, i.e. median module
     * ms ÷ module weight) into the stored anchor with an EWMA, so the calibration tracks the machine
     * without a fresh probe. Seeds from the observation when nothing is stored. Best-effort.
     */
    public static void refine(double observedMsPerWeight, long nowMillis) {
        if (!(observedMsPerWeight > 0)) return;
        Calibration merged = foldRefine(load(), observedMsPerWeight, nowMillis);
        persist(merged);
        MEMO.set(merged);
    }

    /**
     * Pure EWMA/replace fold (no IO). A real build measures the whole per-weight cost (I/O, CAS,
     * annotation processing, fork + framework init) that the synthetic probe can't — so the FIRST
     * real measurement <em>replaces</em> the probe outright, and later ones EWMA-smooth. This
     * converges to reality after one build, not five.
     */
    static Calibration foldRefine(Calibration prev, double observedMsPerWeight, long nowMillis) {
        double next = (prev.present() && prev.measured)
                ? ALPHA * observedMsPerWeight + (1 - ALPHA) * prev.msPerWeight
                : observedMsPerWeight;
        return new Calibration(
                next,
                prev.jvmForkMs,
                prev.javacMs,
                prev.loadAtCalibration,
                prev.present() ? prev.cores : Runtime.getRuntime().availableProcessors(),
                prev.jdk,
                JkVersion.VERSION,
                nowMillis,
                true);
    }

    // --- the host probe ------------------------------------------------------

    /**
     * Measure this host once: fork the resolved JDK's {@code java -version} (dominant, most
     * machine/OS/disk-variable fixed cost) and {@code javac} over a few trivial sources, then derive
     * a self-consistent {@code ms-per-weight}. Returns {@code null} (→ caller keeps the constant) if
     * no JDK resolves without installing, or on any failure.
     *
     * <p>Caveat: {@code java -version} times JVM bootstrap, not JUnit-platform init, so it slightly
     * under-measures a real test fork; learning and {@link #refine} correct this over the next runs.
     */
    private static Calibration probe(Path jdksDir) {
        try {
            Optional<Path> javaHome = resolveJavaHome(jdksDir);
            if (javaHome.isEmpty()) return null;
            Path home = javaHome.get();
            Path javaExe = home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
            Path javacExe = home.resolve("bin").resolve(isWindows() ? "javac.exe" : "javac");
            if (!Files.isRegularFile(javaExe) || !Files.isRegularFile(javacExe)) return null;

            long forkMs = measureFork(javaExe);
            long javacMs = measureJavac(javacExe);
            if (forkMs <= 0 || javacMs <= 0) return null;

            double msPerWeight = deriveMsPerWeight(forkMs, javacMs);
            double load = Math.max(0.0, safeLoadAverage());
            String jdkId = JdkRegistry.identifierFor(home);
            return new Calibration(
                    msPerWeight,
                    forkMs,
                    javacMs,
                    load,
                    Runtime.getRuntime().availableProcessors(),
                    jdkId,
                    JkVersion.VERSION,
                    System.currentTimeMillis(),
                    false); // probe bootstrap — first real build will replace it
        } catch (Exception | LinkageError e) {
            return null; // advisory — never fail over calibration
        }
    }

    /** Resolve a JDK home the same way the build does; empty when only an install would satisfy it. */
    private static Optional<Path> resolveJavaHome(Path jdksDir) {
        try {
            JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
            var req = new JdkResolution.Request(
                    null,
                    cc.jumpkick.config.SessionContext.current().jdkSpec(),
                    System.getenv("JK_JDK"),
                    null,
                    null,
                    0,
                    System::getenv);
            var r = JdkResolution.resolve(req, registry, GlobalDefaultJdk.current(), JdkLts.OFFLINE_LATEST_LTS);
            return r.jdk().map(cc.jumpkick.jdk.InstalledJdk::home);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Median of {@link #FORK_SAMPLES} {@code java -version} forks, discarding the first (cold cache). */
    private static long measureFork(Path javaExe) throws IOException, InterruptedException {
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < FORK_SAMPLES; i++) {
            long ms = timeProcess(new ProcessBuilder(javaExe.toString(), "-version"));
            if (i > 0 && ms >= 0) samples.add(ms); // drop the warmup run
        }
        return median(samples);
    }

    /** Compile a handful of trivial classes and time it — javac cold init + a little throughput. */
    private static long measureJavac(Path javacExe) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("jk-calib");
        try {
            Path out = Files.createDirectory(dir.resolve("out"));
            List<String> cmd = new ArrayList<>();
            cmd.add(javacExe.toString());
            cmd.add("-d");
            cmd.add(out.toString());
            for (int i = 0; i < PROBE_SOURCES; i++) {
                Path src = dir.resolve("Probe" + i + ".java");
                Files.writeString(
                        src,
                        "final class Probe" + i + " { static int f(int x){ return x*" + (i + 1) + "; } }\n",
                        StandardCharsets.UTF_8);
                cmd.add(src.toString());
            }
            return timeProcess(new ProcessBuilder(cmd));
        } finally {
            deleteTree(dir);
        }
    }

    /**
     * {@code ms-per-weight} anchored to the model itself: the measured wall-clock of the probe's
     * fork + compile, over the static weight {@link EffortWeights} would assign that same synthetic
     * unit (a test-startup floor plus a {@value #PROBE_SOURCES}-source compile). Self-consistent by
     * construction, so cold estimates scale with the measured host.
     */
    static double deriveMsPerWeight(long forkMs, long javacMs) {
        int weight = EffortWeights.TEST_STARTUP + EffortWeights.COMPILE_FLOOR + EffortWeights.compileWeight(PROBE_SOURCES);
        return (forkMs + javacMs) / (double) Math.max(1, weight);
    }

    /** Run {@code pb} to completion (output discarded) and return its wall-clock ms, or -1 on failure. */
    private static long timeProcess(ProcessBuilder pb) throws IOException, InterruptedException {
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        long t0 = System.nanoTime();
        Process p = pb.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return p.exitValue() == 0 ? ms : -1;
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) return -1;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static double safeLoadAverage() {
        try {
            return java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    /** Stale when a different jk built it (weights may have changed) or it has aged out (HW/VM drift). */
    static boolean stale(String version, long updated, long nowMillis) {
        if (!JkVersion.VERSION.equals(version)) return true;
        return updated > 0 && nowMillis - updated > MAX_AGE_MILLIS;
    }

    // --- IO ------------------------------------------------------------------

    /** Read the stored calibration at the global path; the safe fallback when missing/stale. */
    private static Calibration readOrAbsent() {
        return readFrom(file(), System.currentTimeMillis());
    }

    /** Read a calibration from {@code f}; the safe fallback when missing, unreadable, or stale. */
    static Calibration readFrom(Path f, long nowMillis) {
        Calibration absent = absent();
        try {
            if (!Files.isRegularFile(f)) return absent;
            TomlParseResult t = Toml.parse(f);
            double mpw = t.getDouble("ms-per-weight") != null ? t.getDouble("ms-per-weight") : 0;
            long updated = t.getLong("updated") != null ? t.getLong("updated") : 0L;
            String version = t.getString("jk-version");
            if (mpw <= 0) return absent;
            if (stale(version, updated, nowMillis)) return absent;
            return new Calibration(
                    mpw,
                    t.getLong("jvm-fork-ms") != null ? t.getLong("jvm-fork-ms") : 0L,
                    t.getLong("javac-ms") != null ? t.getLong("javac-ms") : 0L,
                    t.getDouble("load-at-calibration") != null ? t.getDouble("load-at-calibration") : -1,
                    t.getLong("cores") != null ? Math.toIntExact(t.getLong("cores")) : 0,
                    t.getString("jdk"),
                    version,
                    updated,
                    t.getBoolean("measured") != null ? t.getBoolean("measured") : false);
        } catch (Exception e) {
            return absent; // corrupt/unreadable → treat as uncalibrated
        }
    }

    /** The safe fallback: {@link #present()} is false, {@link #msPerWeight()} returns the constant. */
    private static Calibration absent() {
        return new Calibration(0, 0, 0, -1, 0, null, null, 0, false);
    }

    /** Atomically write {@code c} to the global calibration file; swallows IO failure (advisory). */
    private static void persist(Calibration c) {
        try {
            writeTo(file(), c);
        } catch (IOException | RuntimeException ignored) {
            // advisory — never fail over the calibration file
        }
    }

    /** Atomically write {@code c} to {@code file} (mirrors {@link StepTimings}'s IO). */
    static void writeTo(Path file, Calibration c) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".calibration-", ".tmp");
        try {
            Files.writeString(tmp, c.render(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private String render() {
        return "# jk build-time calibration for this host. Regenerated automatically; safe to delete.\n"
                + "ms-per-weight       = " + round3(msPerWeight) + '\n'
                + "jvm-fork-ms         = " + jvmForkMs + '\n'
                + "javac-ms            = " + javacMs + '\n'
                + "load-at-calibration = " + round3(loadAtCalibration) + '\n'
                + "cores               = " + cores + '\n'
                + "jdk                 = " + quote(jdk == null ? "" : jdk) + '\n'
                + "jk-version          = " + quote(jkVersion == null ? "" : jkVersion) + '\n'
                + "measured            = " + measured + '\n'
                + "updated             = " + updated + '\n';
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Test seam: drop the memo so a freshly-written file is re-read. */
    static void clearMemo() {
        MEMO.set(null);
    }
}
