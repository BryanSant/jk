// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
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
public final class LockCommand implements Callable<Integer> {    @Option(names = "--features", paramLabel = "<a,b,...>", split = ",",
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

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws Exception {
        Path dir = global.workingDir();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Path lockFile = dir.resolve("jk.lock");

        var result = LockFlow.run(dir, cache, features, noDefaultFeatures, repoUrl, "jk lock");
        if (result.workspaceMemberCount() > 0) {
            System.out.println("Workspace: " + result.workspaceMemberCount() + " member"
                    + (result.workspaceMemberCount() == 1 ? "" : "s"));
        }
        if (result.status() != 0) return result.status();
        var lock = result.lockfile();
        System.out.println("Wrote " + lockFile + " (" + lock.packages().size() + " package"
                + (lock.packages().size() == 1 ? "" : "s") + ")");
        return 0;
    }
}
