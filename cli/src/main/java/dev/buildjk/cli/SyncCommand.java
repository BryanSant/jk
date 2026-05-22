// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.http.Http;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.resolver.CacheSync;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** {@code jk sync} — ensure every locked package is present in the local CAS. */
@Command(name = "sync", description = "Reconcile cache to jk.lock.")
public final class SyncCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the CAS cache directory. Default: ~/.jk/cache.")
    Path cacheDir;

    @Override
    public Integer call() throws Exception {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(lockFile)) {
            System.err.println("jk sync: no jk.lock in " + dir + " (run `jk lock` first)");
            return 2;
        }

        Path cache = cacheDir != null
                ? cacheDir
                : Path.of(System.getProperty("user.home"), ".jk", "cache");
        Files.createDirectories(cache);

        Lockfile lock = LockfileReader.read(lockFile);
        CacheSync.Report report = new CacheSync(new Cas(cache), new Http()).sync(lock);

        System.out.println(report.fetched() + " fetched, "
                + report.upToDate() + " up-to-date, "
                + report.skipped() + " skipped");
        if (report.hasErrors()) {
            for (String error : report.errors()) {
                System.err.println("  " + error);
            }
            return 1;
        }
        return 0;
    }
}
