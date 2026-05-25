// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.resolver.CacheSync;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** {@code jk sync} — ensure every locked package is present in the local CAS. */
@Command(name = "sync", description = "Reconcile cache to jk.lock")
public final class SyncCommand implements Callable<Integer> {    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDir;

    @Option(names = "--offline-prepare",
            description = "Prepare for an offline build (accepted, no-op in v0.1).")
    boolean offlinePrepare;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws Exception {
        Path dir = global.workingDir();
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(lockFile)) {
            System.err.println("jk sync: no jk.lock in " + dir + " (run `jk lock` first)");
            return 2;
        }

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
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
