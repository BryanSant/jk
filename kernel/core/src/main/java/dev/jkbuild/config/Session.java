// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.util.JkDirs;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The request-scoped context for one jk invocation — the value the engine (the "server") needs to do
 * work on behalf of a front-end (the "client"). It replaces the process-global {@code ActiveConfig}
 * and the various static tuning channels: everything here is per-request, so two builds can run in
 * one JVM without clobbering each other.
 *
 * <p>Front-ends (CLI, IntelliJ plugin, VS Code extension, web app, GitHub Action) build a {@code
 * Session} and hand it to the engine. The kernel never reaches for process-global state; it reads
 * request state off the {@code Session} threaded through {@code BuildPipeline.Inputs}/{@code
 * PhaseContext}.
 *
 * <p>Grows through the re-foundation (M1→): later milestones fold JVM tuning, JDK/GraalVM selection,
 * the output sink, and the cancellation token onto this record. Use the {@code with*} copy-methods so
 * adding a field never ripples to every construction site.
 *
 * @param config the merged user/project settings (color, offline, force, quiet, working-dir hint, …)
 * @param workingDir absolute working directory for this invocation
 * @param cacheDir jk cache root for this invocation
 * @param jdksDir JDK install root, or {@code null} to use the default ({@link JkDirs#jdks()})
 * @param jvm resolved worker-JVM tuning (max-ram / gc / extra args) for the forks this build spawns
 * @param jdkSpec top-tier JDK selection ({@code --jdk}), or {@code null} — the highest resolution tier
 * @param graalSpec top-tier GraalVM selection ({@code --graal}), or {@code null}
 */
public record Session(
        JkConfig config,
        Path workingDir,
        Path cacheDir,
        Path jdksDir,
        WorkerTuning jvm,
        String jdkSpec,
        String graalSpec) {

    public Session {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(cacheDir, "cacheDir");
        Objects.requireNonNull(jvm, "jvm");
    }

    /** A default session: empty config, current working directory, default cache/JDK roots, no tuning. */
    public static Session defaults() {
        return new Session(
                JkConfig.empty(),
                Path.of("").toAbsolutePath().normalize(),
                JkDirs.cache(),
                null,
                WorkerTuning.NONE,
                null,
                null);
    }

    public Session withConfig(JkConfig newConfig) {
        return new Session(newConfig, workingDir, cacheDir, jdksDir, jvm, jdkSpec, graalSpec);
    }

    public Session withWorkingDir(Path dir) {
        return new Session(config, dir.toAbsolutePath().normalize(), cacheDir, jdksDir, jvm, jdkSpec, graalSpec);
    }

    public Session withCacheDir(Path dir) {
        return new Session(config, workingDir, dir, jdksDir, jvm, jdkSpec, graalSpec);
    }

    public Session withJdksDir(Path dir) {
        return new Session(config, workingDir, cacheDir, dir, jvm, jdkSpec, graalSpec);
    }

    public Session withJvm(WorkerTuning tuning) {
        return new Session(
                config, workingDir, cacheDir, jdksDir, tuning == null ? WorkerTuning.NONE : tuning, jdkSpec, graalSpec);
    }

    /** The top-tier JDK / GraalVM selection ({@code --jdk} / {@code --graal}); blanks normalize to null. */
    public Session withToolchainSpecs(String jdk, String graal) {
        return new Session(config, workingDir, cacheDir, jdksDir, jvm, blankToNull(jdk), blankToNull(graal));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** JDK install root, resolving the default when unset. */
    public Path jdksRoot() {
        return jdksDir != null ? jdksDir : JkDirs.jdks();
    }

    // ---- convenience delegators onto config -------------------------------

    public boolean offline() {
        return config.offlineOr(false);
    }

    public boolean force() {
        return config.forceOr(false);
    }

    public boolean quiet() {
        return config.quietOr(false);
    }

    public boolean verbose() {
        return config.verboseOr(false);
    }
}
