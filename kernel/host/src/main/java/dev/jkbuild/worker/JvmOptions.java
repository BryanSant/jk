// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JVM tuning for the JVMs jk forks — the host and its workers.
 *
 * <p>jk launches a one-shot <b>host</b> JVM that runs the build pipeline, and the
 * host (or the CLI) forks <b>worker</b> JVMs for compilers, tests, etc. Because
 * the host stays resident while a worker runs — and {@code jk build --workers N}
 * forks {@code N} test JVMs at once — a flat "use 70% of RAM" would overcommit.
 * The default here is a conservative {@link #DEFAULT_MAX_RAM_PERCENT}; for a set
 * of {@code N} concurrently-launched JVMs the cap is divided by {@code N}
 * (see {@link #flags(Settings, int)}), so the host plus the live workers fit.
 *
 * <p>Settings resolve highest-precedence-first: <b>CLI flag</b>
 * ({@code --max-ram-percent} / {@code --jvm-arg}) &gt; <b>env</b>
 * ({@code JK_MAX_RAM_PERCENT}, {@code JK_JVM_GC}, {@code JK_JVM_STRING_DEDUP},
 * {@code JK_JVM_ARGS}) &gt; <b>{@code [jvm]} in jk.toml</b> &gt; default. The CLI
 * resolves once and {@linkplain #toEnv exports the effective values as env vars}
 * onto the host process, so the host's worker forks ({@link #flagsFromEnv}) see
 * the same configuration without re-reading anything.
 */
public final class JvmOptions {

    private JvmOptions() {}

    /** Conservative default: host + one worker (or two test workers) fit under it. */
    public static final double DEFAULT_MAX_RAM_PERCENT = 50.0;
    /** Default collector: low-pause, and uncommits idle heap (good for the resident host). */
    public static final String DEFAULT_GC = "zgc";

    public static final String ENV_MAX_RAM      = "JK_MAX_RAM_PERCENT";
    public static final String ENV_GC           = "JK_JVM_GC";
    public static final String ENV_STRING_DEDUP = "JK_JVM_STRING_DEDUP";
    public static final String ENV_ARGS         = "JK_JVM_ARGS";

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
                high.gc()           != null ? high.gc()           : low.gc(),
                high.stringDedup()  != null ? high.stringDedup()  : low.stringDedup(),
                args);
    }

    /** Resolve precedence: {@code cli} &gt; env &gt; {@code projectDir/jk.toml [jvm]} &gt; default. */
    public static Settings resolve(Settings cli, Path projectDir) {
        Settings eff = cli == null ? Settings.NONE : cli;
        eff = overlay(eff, fromEnv());
        if (projectDir != null) eff = overlay(eff, fromToml(projectDir.resolve("jk.toml")));
        return eff;
    }

    /** The {@code JK_*} environment layer. */
    public static Settings fromEnv() {
        return new Settings(
                parseDouble(System.getenv(ENV_MAX_RAM)),
                blankToNull(System.getenv(ENV_GC)),
                parseBool(System.getenv(ENV_STRING_DEDUP)),
                splitArgs(System.getenv(ENV_ARGS)));
    }

    /** The {@code [jvm]} table of a {@code jk.toml}, or {@link Settings#NONE}. Never throws. */
    public static Settings fromToml(Path jkToml) {
        try {
            if (jkToml == null || !Files.isRegularFile(jkToml)) return Settings.NONE;
            TomlParseResult r = Toml.parse(jkToml);
            TomlTable jvm = r.getTable("jvm");
            if (jvm == null) return Settings.NONE;
            // Accept both TOML integer (33) and float (33.0) for the percentage.
            Double maxRam = (jvm.get("max-ram-percent") instanceof Number n) ? n.doubleValue() : null;
            Boolean dedup = jvm.contains("string-dedup") ? jvm.getBoolean("string-dedup") : null;
            List<String> args = new ArrayList<>();
            TomlArray arr = jvm.getArray("args");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) args.add(String.valueOf(arr.get(i)));
            }
            return new Settings(maxRam, blankToNull(jvm.getString("gc")), dedup, args);
        } catch (Exception e) {
            return Settings.NONE;   // a malformed [jvm] never fails a build
        }
    }

    /**
     * Build the JVM flag list for {@code settings}, dividing the heap cap across
     * {@code concurrency} simultaneously-launched JVMs (pass {@code 1} for the
     * host or a lone worker; the test-runner passes its worker count).
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
            case "zgc"           -> out.add("-XX:+UseZGC");
            case "g1"            -> out.add("-XX:+UseG1GC");
            case "none", "default", "" -> { /* leave the JVM's own default */ }
            default              -> out.add("-XX:+UseZGC");
        }
        // String deduplication only has an effect on G1/ZGC; skip it otherwise.
        if (dedup && (gc.equals("zgc") || gc.equals("g1"))) {
            out.add("-XX:+UseStringDeduplication");
        }
        out.addAll(s.extraArgs());
        return out;
    }

    /** Flags resolved from the {@code JK_*} env layer only — used inside the host for worker forks. */
    public static List<String> flagsFromEnv(int concurrency) {
        return flags(fromEnv(), concurrency);
    }

    /**
     * The <em>effective</em> settings as {@code JK_*} env vars, for propagating a
     * CLI-resolved configuration onto the forked host so its own worker forks
     * ({@link #flagsFromEnv}) inherit it. Defaults are baked in so the host need
     * not re-resolve.
     */
    public static Map<String, String> toEnv(Settings settings) {
        Settings s = settings == null ? Settings.NONE : settings;
        Map<String, String> env = new LinkedHashMap<>();
        env.put(ENV_MAX_RAM, fmt(s.maxRamPercent() != null ? s.maxRamPercent() : DEFAULT_MAX_RAM_PERCENT));
        env.put(ENV_GC, s.gc() != null ? s.gc() : DEFAULT_GC);
        env.put(ENV_STRING_DEDUP, String.valueOf(s.stringDedup() == null || s.stringDedup()));
        if (!s.extraArgs().isEmpty()) env.put(ENV_ARGS, String.join(" ", s.extraArgs()));
        return env;
    }

    // ---- helpers --------------------------------------------------------

    /** Whole numbers render without a trailing {@code .0} ({@code 50}, not {@code 50.0}). */
    private static String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Boolean parseBool(String s) {
        if (s == null || s.isBlank()) return null;
        return Boolean.valueOf(s.trim());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static List<String> splitArgs(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : s.trim().split("\\s+")) if (!part.isBlank()) out.add(part);
        return out;
    }
}
