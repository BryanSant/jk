// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import build.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The request-scoped context for one jk invocation — the value the engine (the "server") needs to do
 * work on behalf of a front-end (the "client"). It replaces the process-global {@code ActiveConfig}
 * and the various static tuning channels: everything here is per-request, so two builds can run in
 * one JVM without clobbering each other.
 *
 * <p>Front-ends (CLI, IntelliJ plugin, VS Code extension, web app, GitHub Action) build a {@code
 * Session} and hand it to the engine. The kernel never reaches for process-global state; it reads
 * request state off the {@code Session} threaded through {@code BuildPipelines.Inputs}/{@code
 * StepContext}.
 *
 * <p>Grows through the re-foundation (M1→): later milestones fold the output sink and the
 * cancellation token onto this record. Use the {@code with*} copy-methods so adding a field never
 * ripples to every construction site.
 *
 * @param config the merged user/project settings (color, offline, force, quiet, working-dir hint, …)
 * @param workingDir absolute working directory for this invocation
 * @param cacheDir jk cache root for this invocation
 * @param jdksDir JDK install root, or {@code null} to use the default ({@link JkDirs#jdks()})
 * @param jvm resolved worker-JVM tuning (max-ram / gc / extra args) for the forks this build spawns
 * @param jdkSpec top-tier JDK selection ({@code --jdk}), or {@code null} — the highest resolution tier
 * @param graalSpec top-tier GraalVM selection ({@code --graal}), or {@code null}
 * @param parallelTests {@code --parallel-tests}: run modules' test steps concurrently (default false,
 *     which serializes them through the engine's test gate — shared ports/locks/fixtures)
 * @param cancel per-session cooperative cancellation token (never {@code null}); a front-end holds it
 *     and calls {@link CancelToken#cancel()} to signal, while the engine polls {@link #cancelled()}.
 *     A {@code null} passed to the canonical constructor normalizes to {@link CancelToken#NONE} (an
 *     inert, never-cancelled token); {@link #defaults()} instead carries a fresh {@link
 *     CancelToken#live() live} token so the default front-end path is cancellable out of the box.
 */
public record Session(
        JkConfig config,
        Path workingDir,
        Path cacheDir,
        Path jdksDir,
        PluginTuning jvm,
        String jdkSpec,
        String graalSpec,
        boolean parallelTests,
        CancelToken cancel,
        // The variant selection ("", "release", "release|contentType=demo") and the
        // client-resolved env values (env: indirection — signing secrets). Every pipeline factory's
        // BuildPipelines.Inputs defaults from the session, so any command that installs a
        // selection here parameterizes whatever it builds — run/dev/test/image/native/publish
        // included, not just jk build.
        String variant,
        java.util.Map<String, String> clientEnv) {

    public Session {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(cacheDir, "cacheDir");
        Objects.requireNonNull(jvm, "jvm");
        cancel = (cancel == null) ? CancelToken.NONE : cancel;
        variant = (variant == null) ? "" : variant;
        clientEnv = (clientEnv == null || clientEnv.isEmpty())
                ? java.util.Map.of()
                : java.util.Map.copyOf(clientEnv);
    }

    /** A copy carrying the given variant selection + client-resolved env. */
    public Session withVariant(String variant, java.util.Map<String, String> clientEnv) {
        return new Session(
                config, workingDir, cacheDir, jdksDir, jvm, jdkSpec, graalSpec, parallelTests, cancel,
                variant, clientEnv);
    }

    /**
     * Per-session cooperative cancellation signal. The front-end that owns a {@link Session} keeps a
     * reference to its token and calls {@link #cancel()} to request a stop; engine steps poll {@link
     * #cancelled()} at safe points and unwind. Implementations are thread-safe: the signalling thread
     * (a UI/front-end) and the polling threads (engine workers) differ by design.
     */
    public interface CancelToken {

        /** Whether cancellation has been requested. */
        boolean cancelled();

        /** Request cancellation. Idempotent; safe to call from any thread. */
        void cancel();

        /** A shared, inert token: {@link #cancelled()} is always {@code false} and {@link #cancel()} is a no-op. */
        CancelToken NONE = new CancelToken() {
            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public void cancel() {
                // no-op: NONE is never cancellable
            }
        };

        /** A fresh, live token backed by an {@link AtomicBoolean} — thread-safe and one-shot. */
        static CancelToken live() {
            return new CancelToken() {
                private final AtomicBoolean flag = new AtomicBoolean(false);

                @Override
                public boolean cancelled() {
                    return flag.get();
                }

                @Override
                public void cancel() {
                    flag.set(true);
                }
            };
        }
    }

    /**
     * A default session: empty config, current working directory, default cache/JDK roots, no tuning,
     * and a fresh {@link CancelToken#live() live} cancellation token so a front-end can cancel it.
     */
    public static Session defaults() {
        return new Session(
                JkConfig.empty(),
                Path.of("").toAbsolutePath().normalize(),
                JkDirs.cache(),
                null,
                PluginTuning.NONE,
                null,
                null,
                false,
                CancelToken.live(),
                "",
                null);
    }

    public Session withConfig(JkConfig newConfig) {
        return new Session(
                newConfig, workingDir, cacheDir, jdksDir, jvm, jdkSpec, graalSpec, parallelTests, cancel,
                variant, clientEnv);
    }

    public Session withWorkingDir(Path dir) {
        return new Session(
                config, dir.toAbsolutePath().normalize(), cacheDir, jdksDir, jvm, jdkSpec, graalSpec, parallelTests,
                cancel, variant, clientEnv);
    }

    public Session withCacheDir(Path dir) {
        return new Session(
                config, workingDir, dir, jdksDir, jvm, jdkSpec, graalSpec, parallelTests, cancel,
                variant, clientEnv);
    }

    public Session withJdksDir(Path dir) {
        return new Session(
                config, workingDir, cacheDir, dir, jvm, jdkSpec, graalSpec, parallelTests, cancel,
                variant, clientEnv);
    }

    public Session withJvm(PluginTuning tuning) {
        return new Session(
                config,
                workingDir,
                cacheDir,
                jdksDir,
                tuning == null ? PluginTuning.NONE : tuning,
                jdkSpec,
                graalSpec,
                parallelTests,
                cancel,
                variant,
                clientEnv);
    }

    /** The top-tier JDK / GraalVM selection ({@code --jdk} / {@code --graal}); blanks normalize to null. */
    public Session withToolchainSpecs(String jdk, String graal) {
        return new Session(
                config, workingDir, cacheDir, jdksDir, jvm, blankToNull(jdk), blankToNull(graal), parallelTests,
                cancel, variant, clientEnv);
    }

    public Session withParallelTests(boolean enabled) {
        return new Session(
                config, workingDir, cacheDir, jdksDir, jvm, jdkSpec, graalSpec, enabled, cancel,
                variant, clientEnv);
    }

    /** A copy carrying the given cancellation token ({@code null} → {@link CancelToken#NONE}). */
    public Session withCancel(CancelToken token) {
        return new Session(
                config, workingDir, cacheDir, jdksDir, jvm, jdkSpec, graalSpec, parallelTests, token,
                variant, clientEnv);
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

    /** Whether this session's {@link #cancel() cancellation token} has been signalled. */
    public boolean cancelled() {
        return cancel.cancelled();
    }
}
