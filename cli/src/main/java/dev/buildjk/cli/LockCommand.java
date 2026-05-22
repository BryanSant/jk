// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.http.Http;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileWriter;
import dev.buildjk.model.BuildJk;
import dev.buildjk.repo.MavenRepo;
import dev.buildjk.resolver.LockOrchestrator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk lock} — resolve declared dependencies and write {@code jk.lock}.
 *
 * <p>v0.1 first iteration: resolves main-scope only, hits a single Maven
 * Central-shaped repo. Multi-repo support, repo pinning, and offline modes
 * arrive once {@code repositories.*} parsing lands in build.jk.
 */
@Command(name = "lock", description = "Resolve declared dependencies and write jk.lock.")
public final class LockCommand implements Callable<Integer> {

    private static final URI DEFAULT_REPO =
            URI.create("https://repo.maven.apache.org/maven2/");

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--repo-url",
            description = "Override the Maven repo URL. Default: Maven Central.",
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

        URI url = repoUrl != null ? repoUrl : DEFAULT_REPO;
        MavenRepo repo = new MavenRepo("central", url, new Http(), new Cas(cache));
        LockOrchestrator orchestrator = new LockOrchestrator(repo);

        Lockfile lock;
        try {
            lock = orchestrator.lock(parsed, Jk.VERSION);
        } catch (IOException e) {
            System.err.println("jk lock: " + e.getMessage());
            return 6; // network / repository error per PRD §6
        }
        LockfileWriter.write(lock, lockFile);
        System.out.println("Wrote " + lockFile + " (" + lock.packages().size() + " package"
                + (lock.packages().size() == 1 ? "" : "s") + ")");
        return 0;
    }
}
