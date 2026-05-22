// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.hocon.WorkspaceLoader;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileWriter;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.WorkspaceMerge;
import dev.buildjk.repo.RepoGroup;
import dev.buildjk.resolver.LockOrchestrator;
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
 * <p>Reads repositories + features from the project's build.jk. Feature
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
            description = "Override the CAS cache directory. Default: ~/.jk/cache.",
            hidden = true)
    Path cacheDir;

    @Override
    public Integer call() throws Exception {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildFile = dir.resolve("build.jk");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk lock: no build.jk in " + dir);
            return 2; // EX_CONFIG
        }

        Path cache = cacheDir != null
                ? cacheDir
                : Path.of(System.getProperty("user.home"), ".jk", "cache");
        Files.createDirectories(cache);

        BuildJk parsed;
        try {
            parsed = BuildJkParser.parse(buildFile);
        } catch (RuntimeException e) {
            System.err.println("jk lock: " + e.getMessage());
            return 2;
        }

        // Workspace: load each member's build.jk and merge deps so the
        // workspace root produces one combined jk.lock per PRD §13.2.
        BuildJk effective = parsed;
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
