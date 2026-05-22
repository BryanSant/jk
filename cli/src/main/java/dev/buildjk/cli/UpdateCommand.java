// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileWriter;
import dev.buildjk.model.BuildJk;
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
 * {@code jk update} — re-resolve declared dependencies and overwrite
 * {@code jk.lock}. Same pipeline as {@code jk lock}; the difference is
 * intent: {@code lock} is "make sure a lock exists", {@code update} is
 * "throw away whatever I have and resolve fresh."
 *
 * <p>{@code --precise &lt;coord&gt;@&lt;ver&gt;} per PRD §6 is accepted
 * but a no-op until selective resolution lands.
 */
@Command(name = "update", description = "Re-resolve declared dependencies and rewrite jk.lock")
public final class UpdateCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--precise", paramLabel = "<coord>@<ver>",
            description = "Pin a single coord to a version for this update (not yet implemented).")
    String precise;

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
            System.err.println("jk update: no build.jk in " + dir);
            return 2;
        }

        if (precise != null && !precise.isBlank()) {
            // Accept but don't act on it yet — the resolver doesn't have
            // a "selective re-resolve" entry point.
            System.err.println("jk update: --precise is recognized but not yet implemented; "
                    + "performing a full re-resolve instead.");
        }

        Path cache = cacheDir != null
                ? cacheDir
                : Path.of(System.getProperty("user.home"), ".jk", "cache");
        Files.createDirectories(cache);

        BuildJk parsed;
        try {
            parsed = BuildJkParser.parse(buildFile);
        } catch (RuntimeException e) {
            System.err.println("jk update: " + e.getMessage());
            return 2;
        }

        RepoGroup repos = RepoGroupBuilder.buildFor(parsed, repoUrl, new Cas(cache));
        LockOrchestrator orchestrator = new LockOrchestrator(repos);

        Lockfile lock;
        try {
            lock = orchestrator.lock(parsed, Jk.VERSION, features, !noDefaultFeatures);
        } catch (IOException e) {
            System.err.println("jk update: " + e.getMessage());
            return 6;
        }
        LockfileWriter.write(lock, lockFile);
        System.out.println("Updated " + lockFile + " (" + lock.packages().size() + " package"
                + (lock.packages().size() == 1 ? "" : "s") + ")");
        return 0;
    }
}
