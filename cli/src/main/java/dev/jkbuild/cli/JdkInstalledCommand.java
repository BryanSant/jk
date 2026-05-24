// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk installed} — same box-drawn table as {@code jk jdk list},
 * filtered to rows whose status is {@code default} or {@code installed}
 * (i.e., what's actually on this machine). Catalog-only "available" rows
 * are dropped.
 *
 * <p>The flags mirror {@code jk jdk list} so a user can pass the same
 * hidden test overrides ({@code --jdks-dir}, {@code --feed-url},
 * {@code --cache-file}) without rewriting muscle memory.
 */
@Command(name = "installed",
        description = "List JDKs already installed locally")
public final class JdkInstalledCommand implements Callable<Integer> {

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Option(names = "--offline",
            description = "Skip the JetBrains catalog fetch; vendor names will be blank for "
                    + "JDKs without a cached catalog entry.")
    boolean offline;

    @Option(names = "--feed-url", hidden = true,
            description = "Override the JetBrains JDK feed URL (for tests).")
    URI feedUrl;

    @Option(names = "--cache-file", hidden = true,
            description = "Override the catalog cache path (for tests).")
    Path cacheFile;

    @Override
    public Integer call() throws Exception {
        // Delegate to the list-command renderer with the installed-only
        // filter on. Keeps the table layout / colors / NO_COLOR handling
        // in one place; this command only changes which rows survive.
        JdkListCommand list = new JdkListCommand();
        list.jdksDir = jdksDir;
        list.offline = offline;
        list.feedUrl = feedUrl;
        list.cacheFile = cacheFile;
        list.installedOnly = true;
        return list.call();
    }
}
