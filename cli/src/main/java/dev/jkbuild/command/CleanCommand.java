// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.config.GlobalConfig;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.task.CacheGc;
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
                Opt.flag("GC the shared cache: purge blobs idle 90+ days.", "--cache"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        boolean keepArtifacts = in.isSet("keep-artifacts");
        boolean gcCache = in.isSet("cache");
        Path dir = new GlobalOptions().workingDir();
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
                // Legacy pre-layout directories, best-effort.
                deleteRecursively(projectDir.resolve("build"), stats);
                deleteRecursively(projectDir.resolve(".jk").resolve("generated"), stats);
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
        return 0;
    }

    /** Run the cache GC and print a one-line summary. */
    private static void gcCache() throws IOException {
        CacheGc.Report report;
        try (Spinner spinner = Spinner.show(CliOutput.stdout(), "Collecting cache...")) {
            report = CacheGc.run(JkDirs.cache(), false);
        }
        boolean nerdfont = GlobalConfig.nerdfont();
        if (report.purgedBlobs() == 0) {
            CliOutput.out(GoalWedge.chipLine(Glyphs.CHECK, "Cache GC", nerdfont, "nothing idle past 90 days"));
        } else {
            String msg = String.format(
                    "purged %,d blob%s (%s), %,d repo link%s",
                    report.purgedBlobs(),
                    report.purgedBlobs() == 1 ? "" : "s",
                    CacheCommand.fmtBytes(report.freedBytes()),
                    report.repoLinksRemoved(),
                    report.repoLinksRemoved() == 1 ? "" : "s");
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
        try {
            JkBuild root = JkBuildParser.parse(rootToml);
            if (root.isWorkspaceRoot()) {
                for (String module : root.workspace().modules()) {
                    Path moduleDir = workspaceRoot.resolve(module);
                    if (Files.isDirectory(moduleDir)) dirs.add(moduleDir);
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return dirs;
    }

    private static Path resolveWorkspaceRoot(Path dir) {
        try {
            Optional<Path> root = WorkspaceLocator.findRoot(dir);
            return root.orElse(dir);
        } catch (IOException ignored) {
            return dir;
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
