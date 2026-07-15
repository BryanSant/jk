// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Resolves the effective {@link PluginTuning} for a request from its config layers — CLI flags,
 * {@code JK_JVM_*} environment, and the project's {@code jk.toml [jvm]} table. The client installs
 * only its flag/env layers ({@link #resolveClient}) on the {@link Session}; the {@code [jvm]}
 * table is a jk.toml read and therefore engine-side — {@code build.jumpkick.worker.JvmOptions}
 * overlays it at worker-fork time via {@link #overlayProject} (thin-client contract).
 */
public final class PluginTunings {

    private PluginTunings() {}

    public static final String ENV_MAX_RAM = "JK_MAX_RAM_PERCENT";
    public static final String ENV_GC = "JK_JVM_GC";
    public static final String ENV_STRING_DEDUP = "JK_JVM_STRING_DEDUP";
    public static final String ENV_ARGS = "JK_JVM_ARGS";

    /**
     * Overlay {@code high} onto {@code low}: high's non-null scalars win; args concatenate (low
     * first).
     */
    private static PluginTuning overlay(PluginTuning high, PluginTuning low) {
        List<String> args = new ArrayList<>(low.extraArgs());
        args.addAll(high.extraArgs());
        return new PluginTuning(
                high.maxRamPercent() != null ? high.maxRamPercent() : low.maxRamPercent(),
                high.gc() != null ? high.gc() : low.gc(),
                high.stringDedup() != null ? high.stringDedup() : low.stringDedup(),
                args);
    }

    /**
     * Resolve precedence: {@code cli} &gt; env &gt; {@code projectDir/jk.toml [jvm]} &gt; default.
     * Engine/test-side only — the client resolves {@link #resolveClient(PluginTuning)} and the
     * engine overlays the project layer at worker-fork time ({@link #overlayProject}).
     */
    public static PluginTuning resolve(PluginTuning cli, Path projectDir) {
        return overlayProject(resolveClient(cli), projectDir);
    }

    /**
     * The client-side layers only — CLI flags over {@code JK_JVM_*} env. No file I/O and no TOML:
     * the {@code jk.toml [jvm]} table is interpreted engine-side at worker-fork time (thin-client
     * contract), so a client of any age gets current-engine semantics and the native image never
     * reaches {@link #fromToml}.
     */
    public static PluginTuning resolveClient(PluginTuning cli) {
        return overlay(cli == null ? PluginTuning.NONE : cli, fromEnv());
    }

    /**
     * Overlay {@code base} (the client's flag/env layers) onto {@code projectDir/jk.toml [jvm]}:
     * base's scalars win; the table's args run first. Engine-side only (tomlj).
     */
    public static PluginTuning overlayProject(PluginTuning base, Path projectDir) {
        PluginTuning eff = base == null ? PluginTuning.NONE : base;
        return projectDir == null ? eff : overlay(eff, fromToml(projectDir.resolve("jk.toml")));
    }

    /** The {@code JK_*} environment layer. Coercion via the shared {@link EnvValues}. */
    public static PluginTuning fromEnv() {
        return new PluginTuning(
                EnvValues.doubleValue(System::getenv, ENV_MAX_RAM).orElse(null),
                EnvValues.string(System::getenv, ENV_GC).orElse(null),
                EnvValues.bool(System::getenv, ENV_STRING_DEDUP).orElse(null),
                splitArgs(System.getenv(ENV_ARGS)));
    }

    /**
     * The {@code [jvm]} table of a {@code jk.toml}, or {@link PluginTuning#NONE}. Never throws — a
     * missing/malformed file or table degrades to {@code NONE}. Coercion via the shared {@link
     * TomlValues} ({@code max-ram-percent} accepts a TOML integer or float; {@code args} keeps only
     * string elements).
     */
    public static PluginTuning fromToml(Path jkToml) {
        Optional<TomlParseResult> parsed = TomlValues.parse(jkToml);
        if (parsed.isEmpty()) return PluginTuning.NONE;
        TomlTable jvm = parsed.get().getTable("jvm");
        if (jvm == null) return PluginTuning.NONE;
        return new PluginTuning(
                TomlValues.optDouble(jvm, "max-ram-percent").orElse(null),
                TomlValues.optString(jvm, "gc").orElse(null),
                TomlValues.optBoolean(jvm, "string-dedup").orElse(null),
                TomlValues.stringList(jvm, "args"));
    }

    private static List<String> splitArgs(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : s.trim().split("\\s+")) if (!part.isBlank()) out.add(part);
        return out;
    }
}
