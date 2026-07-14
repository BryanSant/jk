// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.config.GlobalConfig;
import dev.jkbuild.config.WorkspaceScan;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@code jk clean} — delete generated build outputs for the workspace root and every declared
 * module.
 *
 * <p>Each module owns its own {@code target/} directory (final artifacts + build intermediates). By
 * default {@code jk clean} removes the full {@code target/} tree for every project directory. With
 * {@code --keep-artifacts}, only {@code target/} (compiler outputs, test reports, etc.) is removed
 * — handy when you want a fresh compile but still need the existing jars around for downstream
 * consumers.
 *
 * <p>The shared cache at {@code $JK_CACHE_DIR} is left alone by default — that's per-machine state,
 * not per-project. Pass {@code --cache} to also run a cache GC: a mark-and-sweep that purges
 * unreferenced CAS blobs (and their {@code repo/} mirror links) idle for more than 90 days, then
 * compacts the access log.
 *
 * <p>{@code jk clean --force} is the full per-project hammer (Cargo's {@code cargo clean -p}
 * analog): after removing the local files it also invalidates this project's (and its
 * workspace's) ACTION-CACHE entries — the next build can't restore a cached compile, test
 * marker, or packaged artifact. CAS blobs are untouched (content-addressed and shared; the
 * sweep reclaims them later).
 *
 * <p>The first command ported off picocli to jk's own {@link CliCommand} model
 * (docs/plugin-refactor.md §5).
 */
public final class CleanCommand implements CliCommand {

    @Override
    public String name() {
        return "clean";
    }

    @Override
    public String description() {
        return "Delete generated build outputs";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Delete only build/ intermediates; keep artifacts.", "--keep-artifacts"),
                Opt.flag("GC the shared cache: purge blobs idle 90+ days.", "--cache"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide());
    }

    @Override
    public int run(Invocation in) throws IOException {
        boolean keepArtifacts = in.isSet("keep-artifacts");
        boolean gcCache = in.isSet("cache");
        boolean force = GlobalOptions.from(in).force;
        Path cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        Path dir = GlobalOptions.from(in).workingDir();
        Path workspaceRoot = resolveWorkspaceRoot(dir);
        List<Path> projectDirs = collectProjectDirs(workspaceRoot);

        long startMs = System.currentTimeMillis();
        long[] stats = {0L, 0L}; // [fileCount, totalBytes]

        try (Spinner spinner = Spinner.show(CliOutput.stdout(), "Cleaning...")) {
            for (Path projectDir : projectDirs) {
                if (!keepArtifacts) {
                    deleteRecursively(projectDir.resolve("target"), stats);
                } else {
                    // Keep final artifacts (jars, native binaries) in target/; remove
                    // build intermediates by their known subdirectory names.
                    Path target = projectDir.resolve("target");
                    for (String sub :
                            List.of("classes", "kotlin", "resources", "generated", "tmp", "test-results", "reports")) {
                        deleteRecursively(target.resolve(sub), stats);
                    }
                }
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;

        boolean nerdfont = GlobalConfig.nerdfont();
        if (stats[0] == 0) {
            CliOutput.out(GoalWedge.chipLine(Glyphs.CHECK, "Clean", nerdfont, "Nothing to remove"));
        } else {
            String removed = Theme.colorize("Removed", Theme.active().focused());
            String stats_ = String.format(
                    "%,d file%s, %s total", stats[0], stats[0] == 1 ? "" : "s", CacheCommand.fmtBytes(stats[1]));
            String inTime = ConsoleSpec.took(Duration.ofMillis(elapsedMs));
            CliOutput.out(
                    GoalWedge.chipLine(Glyphs.CHECK, "Clean", nerdfont, removed + " " + stats_ + " " + inTime));
        }

        if (gcCache) {
            gcCache();
        }
        if (force) {
            // The hammer's second half: this project's action-cache entries go too, so the
            // next build genuinely starts from scratch. No prompt — --force IS the consent.
            int cleared = clearProjectActionCache(dir, cacheDirOverride);
            if (cleared != 0) return cleared;
        }
        return 0;
    }

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()} for the full rationale. A real {@code jk clean --cache}
     * hosts the GC on the engine (Wave 4: it mutates the shared CAS the engine's pipelines read, so
     * it runs as an idle-boundary job, riding {@code jk cache prune}'s wire vocabulary).
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    /** Run the cache GC (engine-hosted for a real invocation) and print a one-line summary. */
    private static void gcCache() throws IOException {
        long purgedBlobs;
        long freedBytes;
        long repoLinksRemoved;
        if (engineDisabledForTests()) {
            dev.jkbuild.cli.engine.EngineClient.CacheMaintSummary summary;
            try (Spinner spinner = Spinner.show(CliOutput.stdout(), "Collecting cache...")) {
                summary = dev.jkbuild.cli.engine.InProcessEngine.require().cacheGc(JkDirs.cache());
            }
            purgedBlobs = Math.max(0, summary.files());
            freedBytes = Math.max(0, summary.bytes());
            repoLinksRemoved = Math.max(0, summary.repoLinks());
        } else {
            // Hosted: the spinner stays client-side (the goal has no per-file progress worth a
            // bar); the counts ride the terminal goal-finish.
            var summary = new dev.jkbuild.cli.engine.EngineClient.CacheMaintSummary[1];
            try (Spinner spinner = Spinner.show(CliOutput.stdout(), "Collecting cache...")) {
                dev.jkbuild.run.GoalResult result = dev.jkbuild.cli.engine.EngineClient.runCacheMaintenance(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.CacheMaintRequest(
                                "gc", JkDirs.cache(), 0, false, false, null, false),
                        phases -> new dev.jkbuild.run.GoalListener() {},
                        (external, pipelines) -> {},
                        summary);
                if (!result.success() || summary[0] == null) {
                    CliOutput.err("jk clean: cache GC failed — run `jk engine status` for details");
                    return;
                }
            }
            purgedBlobs = Math.max(0, summary[0].files());
            freedBytes = Math.max(0, summary[0].bytes());
            repoLinksRemoved = Math.max(0, summary[0].repoLinks());
        }
        boolean nerdfont = GlobalConfig.nerdfont();
        if (purgedBlobs == 0) {
            CliOutput.out(GoalWedge.chipLine(Glyphs.CHECK, "Cache GC", nerdfont, "nothing idle past 90 days"));
        } else {
            String msg = String.format(
                    "purged %,d blob%s (%s), %,d repo link%s",
                    purgedBlobs,
                    purgedBlobs == 1 ? "" : "s",
                    CacheCommand.fmtBytes(freedBytes),
                    repoLinksRemoved,
                    repoLinksRemoved == 1 ? "" : "s");
            CliOutput.out(GoalWedge.chipLine(Glyphs.CHECK, "Cache GC", nerdfont, msg));
        }
    }

    /**
     * Returns the workspace root plus every declared module directory. Falls back to just {@code
     * [workspaceRoot]} when parsing fails or there are no modules (single-project).
     */
    private static List<Path> collectProjectDirs(Path workspaceRoot) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(workspaceRoot);
        Path rootToml = workspaceRoot.resolve("jk.toml");
        if (!Files.exists(rootToml)) return dirs;
        var info = BuildCommand.projectInfoOrNull(workspaceRoot);
        if (info != null && info.workspaceRoot()) {
            for (String module : info.moduleDirs()) {
                Path moduleDir = workspaceRoot.resolve(module);
                if (Files.isDirectory(moduleDir)) dirs.add(moduleDir);
            }
        }
        return dirs;
    }

    private static Path resolveWorkspaceRoot(Path dir) {
        return WorkspaceScan.findRoot(dir).orElse(dir);
    }

    /** Invalidate this project's (+ workspace's) action-cache entries — `jk cache clear -y`. */
    private static int clearProjectActionCache(Path projectDir, Path cacheDirOverride) {
        if (!Files.isRegularFile(projectDir.resolve("jk.toml"))) {
            // Not a project dir: nothing project-scoped to clear; the file clean already ran.
            return 0;
        }
        Path root = CacheCommand.resolveCacheRoot(cacheDirOverride);
        GoalConsole.Mode mode = GoalConsole.modeFor(new GlobalOptions());
        if (Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"))) {
            try {
                return dev.jkbuild.cli.engine.InProcessEngine.require()
                        .clearInProcess(root, projectDir, false, mode);
            } catch (IOException e) {
                CliOutput.err("jk clean --force: " + e.getMessage());
                return dev.jkbuild.model.command.Exit.SOFTWARE;
            }
        }
        var summary = new dev.jkbuild.cli.engine.EngineClient.CacheMaintSummary[1];
        ConsoleSpec spec = CacheCommand.CacheClearCommand.clearSpec(
                false,
                () -> summary[0] != null ? summary[0].files() : 0L,
                () -> summary[0] != null ? summary[0].bytes() : 0L);
        try {
            var result = dev.jkbuild.cli.engine.EngineClient.runCacheMaintenance(
                    dev.jkbuild.engine.EnginePaths.current(),
                    new dev.jkbuild.cli.engine.EngineClient.CacheMaintRequest(
                            "clear", root, 0, false, false, null, false, projectDir),
                    phases -> GoalConsole.chooseConsoleListener(phases, mode, spec, "Cache"),
                    CacheCommand::printWait,
                    summary);
            return result.success() ? 0 : 1;
        } catch (IOException e) {
            CliOutput.err("jk clean --force: " + e.getMessage());
            return dev.jkbuild.model.command.Exit.SOFTWARE;
        }
    }

    private static void deleteRecursively(Path root, long[] stats) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    if (Files.isRegularFile(p)) {
                        stats[1] += Files.size(p);
                        stats[0]++;
                    }
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
