// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.WorkspaceMerge;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.LockOrchestrator;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk lock} — resolve declared dependencies and write {@code jk.lock}.
 *
 * <p>Reads repositories + features from the project's jk.toml. Feature
 * selection: {@code --features=A,B} adds those features on top of the
 * declared {@code features.default}; {@code --no-default-features}
 * disables the default list entirely. Cargo semantics.
 */
@Command(name = "lock", description = "Resolve declared dependencies and write jk.lock")
public final class LockCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--features", paramLabel = "<a,b,...>", split = ",",
            description = "Activate the listed features in addition to defaults.")
    List<String> features = List.of();

    @Option(names = "--no-default-features",
            description = "Don't activate the project's default features.")
    boolean noDefaultFeatures;

    @Option(names = "--repo-url",
            description = "Override declared repos with a single URL.",
            hidden = true)
    URI repoUrl;

    @Option(names = "--cache-dir",
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
            hidden = true)
    Path cacheDir;

    @Override
    public Integer call() throws Exception {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk lock: no jk.toml in " + dir);
            return 2; // EX_CONFIG
        }

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        JkBuild parsed;
        try {
            parsed = JkBuildParser.parse(buildFile);
        } catch (RuntimeException e) {
            System.err.println("jk lock: " + e.getMessage());
            return 2;
        }

        // Workspace: load each member's jk.toml and merge deps so the
        // workspace root produces one combined jk.lock per PRD §13.2.
        JkBuild effective = parsed;
        if (parsed.isWorkspaceRoot()) {
            try {
                var members = WorkspaceLoader.loadMembers(dir, parsed);
                effective = WorkspaceMerge.merge(parsed, members.values());
                System.out.println("Workspace: " + members.size() + " member"
                        + (members.size() == 1 ? "" : "s"));
            } catch (RuntimeException e) {
                System.err.println("jk lock: " + e.getMessage());
                return 2;
            }
        }

        RepoGroup repos = RepoGroupBuilder.buildFor(effective, repoUrl, new Cas(cache));
        LockOrchestrator orchestrator = new LockOrchestrator(repos);

        Lockfile lock;
        try {
            lock = orchestrator.lock(effective, Jk.VERSION, features, !noDefaultFeatures);
        } catch (IOException e) {
            System.err.println("jk lock: " + e.getMessage());
            return 6;
        }
        LockfileWriter.write(lock, lockFile);
        System.out.println("Wrote " + lockFile + " (" + lock.packages().size() + " package"
                + (lock.packages().size() == 1 ? "" : "s") + ")");
        return 0;
    }
}
