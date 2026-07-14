// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.config.JkCacheConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Decide whether an opportunistic prune should fire in the tail of {@code jk sync} / {@code jk
 * build}, and (if so) launch it as a detached subprocess.
 *
 * <p>Trigger: {@code auto-prune = true} AND ({@code .last-pruned} is missing OR older than {@code
 * prune-interval-days}). Size-based triggers add complexity with little benefit — interval-based is
 * enough to keep the cache bounded over time.
 *
 * <p>The actual prune runs as a detached child process via {@code jk cache prune --background}. The
 * flock inside the child ensures only one prune runs at a time across concurrent invocations. The
 * parent doesn't wait — the build/sync command exits immediately after the spawn.
 *
 * <p><b>In-process path:</b> the resident engine never spawns this — a
 * successful engine-hosted build/sync enqueues an engine-internal prune that runs at the next idle
 * boundary (no pipelines in flight; see {@code EngineServer}). Only the test-only in-process
 * command paths still use the detached spawn, since they run with no engine by definition; {@link
 * #shouldRun} is the shared cadence check both paths consult.
 */
public final class CachePruneScheduler {

    /** Sentinel filename written after each successful prune. */
    public static final String LAST_PRUNED_FILE = ".last-pruned";

    private CachePruneScheduler() {}

    /**
     * Run if cued; do nothing otherwise. Errors are swallowed — the opportunistic prune is a hygiene
     * optimisation, never load-bearing.
     */
    public static void maybeRun(JkCacheConfig config, Path cacheRoot, String jkExe) {
        if (!config.autoPrune()) return;
        try {
            if (!shouldRun(config, cacheRoot)) return;
            spawnDetached(config, cacheRoot, jkExe);
        } catch (IOException ignored) {
            // Best-effort.
        }
    }

    /** True if the configured cadence calls for a prune now. */
    public static boolean shouldRun(JkCacheConfig config, Path cacheRoot) throws IOException {
        Path stamp = cacheRoot.resolve(LAST_PRUNED_FILE);
        if (!Files.isRegularFile(stamp)) return true;
        long last;
        try {
            last = Long.parseLong(
                    Files.readString(stamp, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return true;
        }
        long intervalMillis = (long) config.pruneIntervalDays() * 24L * 60L * 60L * 1000L;
        return (System.currentTimeMillis() - last) > intervalMillis;
    }

    /**
     * Build the equivalent of {@code jk cache prune --background --sweep [--max-size <N>G]} command
     * line. Returned verbatim so the spawn site can audit / log it.
     */
    static List<String> commandFor(JkCacheConfig config, Path cacheRoot, String jkExe) {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(jkExe);
        cmd.add("cache");
        cmd.add("prune");
        cmd.add("--background");
        cmd.add("--sweep");
        cmd.add("--cache-dir");
        cmd.add(cacheRoot.toAbsolutePath().toString());
        cmd.add("--older-than");
        cmd.add(Integer.toString(config.recordTtlDays()));
        config.maxSizeGb().ifPresent(gb -> {
            cmd.add("--max-size");
            cmd.add(gb + "G");
        });
        return cmd;
    }

    private static void spawnDetached(JkCacheConfig config, Path cacheRoot, String jkExe) throws IOException {
        if (jkExe == null || jkExe.isBlank()) return;
        ProcessBuilder pb = new ProcessBuilder(commandFor(config, cacheRoot, jkExe));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        // Close stdin so the child sees EOF immediately on any read.
        // The parent doesn't wait for the child — it'll outlive us.
        p.getOutputStream().close();
    }

    /**
     * Best-effort resolution of the absolute path to the running {@code jk} binary. Lives here so
     * engine doesn't depend on cli.
     *
     * <p>The running executable IS jk (whatever it's named) unless the process is a JVM launcher —
     * a JVM-dist run's command is {@code .../bin/java}, which can't be re-invoked as jk, so it
     * resolves empty and {@code JK_EXE} is the override. (A filename heuristic like
     * {@code contains("jk")} is wrong in both directions: it rejects a renamed native binary and
     * accepts a java launcher installed under a path that happens to contain "jk".)
     */
    public static Optional<String> resolveJkExe() {
        String envOverride = System.getenv("JK_EXE");
        if (envOverride != null && !envOverride.isBlank()) {
            return Optional.of(envOverride);
        }
        Path candidate = null;
        try {
            // Authoritative on Linux, for native images and JVMs alike.
            candidate = Files.readSymbolicLink(Path.of("/proc/self/exe"));
        } catch (IOException | RuntimeException ignored) {
            // not Linux (or /proc unavailable) — fall through
        }
        if (candidate == null) {
            try {
                candidate = ProcessHandle.current()
                        .info()
                        .command()
                        .map(Path::of)
                        .orElse(null);
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        if (candidate != null && !isJavaLauncher(candidate)) {
            return Optional.of(candidate.toAbsolutePath().toString());
        }
        return Optional.empty();
    }

    private static boolean isJavaLauncher(Path p) {
        String name = p.getFileName() == null
                ? ""
                : p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe");
    }
}
