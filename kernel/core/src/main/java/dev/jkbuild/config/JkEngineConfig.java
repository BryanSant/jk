// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * User-global policy for the resident build engine, parsed from the {@code [engine]} table of
 * {@code ~/.jk/config.toml} ({@link JkDirs#userConfigFile()}). See {@code docs/engine.md}.
 *
 * <p>Deliberately <strong>not project-overridable</strong>, for the same reason as {@link
 * JkCacheConfig}: the engine's lifetime is a per-machine/per-user policy, not something a checked-out
 * project should be able to force on another project sharing the same {@code ~/.jk}.
 *
 * <pre>{@code
 * [engine]
 * idle-minutes = 120   # JK_ENGINE_IDLE_MINUTES   see idleMinutes() javadoc for 0 / -1
 * max-heap-mb  = 256   # JK_ENGINE_MAX_HEAP_MB    engine-process heap ceiling; 0 = uncapped
 * }</pre>
 *
 * <p>Read once when an engine starts (or is spawned) — a running engine does not hot-reload this
 * file; {@code jk engine stop} followed by a fresh lazy-start picks up a changed value.
 */
public record JkEngineConfig(int idleMinutes, int maxHeapMb) {

    public static final int DEFAULT_IDLE_MINUTES = 120;

    /**
     * Default heap ceiling for the engine process itself ({@code -Xmx}, applied by the spawner —
     * see {@code EngineClient.spawn}). This IS the ~256 MiB memory target of {@code
     * docs/engine.md}, enforced: the engine is pure orchestration — the heavy lifting stays in
     * separately-sized worker JVMs — so it never needs the uncapped runtime default (¼ of RAM).
     */
    public static final int DEFAULT_MAX_HEAP_MB = 256;

    /**
     * Initial heap the spawner requests ({@code -Xms}, clamped to {@link #maxHeapMb} when the user
     * configures a smaller ceiling). The engine is long-lived; pre-sizing avoids growth churn.
     */
    public static final int MIN_HEAP_MB = 32;

    public static final JkEngineConfig DEFAULTS = new JkEngineConfig(DEFAULT_IDLE_MINUTES, DEFAULT_MAX_HEAP_MB);

    /** Convenience for callers/tests that only care about the idle policy. */
    public JkEngineConfig(int idleMinutes) {
        this(idleMinutes, DEFAULT_MAX_HEAP_MB);
    }

    /**
     * The effective engine configuration for this machine: the user-global {@code
     * ~/.jk/config.toml} (missing/malformed → {@link #DEFAULTS}), overridden by {@code
     * JK_ENGINE_IDLE_MINUTES} / {@code JK_ENGINE_MAX_HEAP_MB} when set (env &gt; user-config &gt;
     * default).
     */
    public static JkEngineConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkEngineConfig resolve(Path userConfig, Function<String, String> env) {
        JkEngineConfig base = fromToml(userConfig);
        return new JkEngineConfig(
                EnvValues.intValue(env, "JK_ENGINE_IDLE_MINUTES")
                        .filter(JkEngineConfig::valid)
                        .orElse(base.idleMinutes),
                EnvValues.intValue(env, "JK_ENGINE_MAX_HEAP_MB")
                        .filter(JkEngineConfig::validHeap)
                        .orElse(base.maxHeapMb));
    }

    /**
     * Load from a TOML file's {@code [engine]} table. Missing file, missing table, or malformed
     * file → {@link #DEFAULTS}. An out-of-range {@code idle-minutes} (anything below {@code -1})
     * or {@code max-heap-mb} (negative) also falls back to its default — the engine layer is
     * advisory, never a build-breaking gate.
     */
    public static JkEngineConfig fromToml(Path file) {
        // TomlScan, not tomlj: the CLIENT reads this before it can spawn an engine — the
        // engine-spawn path must not require a TOML parser (thin-client plan Milestone C).
        TomlScan scan = TomlScan.scan(file, "engine.idle-minutes", "engine.max-heap-mb");
        int idleMinutes = scanInt(scan, "engine.idle-minutes")
                .filter(JkEngineConfig::valid)
                .orElse(DEFAULTS.idleMinutes);
        int maxHeapMb = scanInt(scan, "engine.max-heap-mb")
                .filter(JkEngineConfig::validHeap)
                .orElse(DEFAULTS.maxHeapMb);
        return new JkEngineConfig(idleMinutes, maxHeapMb);
    }

    private static Optional<Integer> scanInt(TomlScan scan, String key) {
        String v = scan.get(key);
        if (v == null) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return Optional.empty(); // malformed value — advisory layer, fall back
        }
    }

    private static boolean valid(int idleMinutes) {
        return idleMinutes >= -1;
    }

    private static boolean validHeap(int maxHeapMb) {
        return maxHeapMb >= 0; // 0 = uncapped (inherit the runtime's own default)
    }

    /** {@code true}: cap the engine process's heap at {@link #maxHeapMb} MiB when spawning it. */
    public boolean heapCapped() {
        return maxHeapMb > 0;
    }

    /** The {@code -Xms} the spawner should request: {@link #MIN_HEAP_MB}, never above the cap. */
    public int minHeapMb() {
        return heapCapped() ? Math.min(MIN_HEAP_MB, maxHeapMb) : MIN_HEAP_MB;
    }

    /** {@code true}: shut down the instant the in-flight workload drains, don't linger at all. */
    public boolean exitAsSoonAsIdle() {
        return idleMinutes == 0;
    }

    /** {@code true}: never self-terminate — only an explicit stop or process death ends the engine. */
    public boolean neverExpires() {
        return idleMinutes == -1;
    }
}
