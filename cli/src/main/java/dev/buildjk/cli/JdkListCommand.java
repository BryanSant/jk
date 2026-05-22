// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.discovery.SymlinkProvisioner;
import dev.buildjk.jdk.InstalledJdk;
import dev.buildjk.jdk.JdkRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk list} — print every JDK present under {@code ~/.jk/jdks/},
 * with a provenance column distinguishing locally-downloaded installs from
 * symlinks into SDKMAN / JBang / etc., and flagging any broken links.
 */
@Command(name = "list", description = "List installed JDKs")
public final class JdkListCommand implements Callable<Integer> {

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: ~/.jk/jdks.")
    Path jdksDir;

    @Override
    public Integer call() throws Exception {
        Path jdksRoot = jdksDir != null
                ? jdksDir
                : Path.of(System.getProperty("user.home"), ".jk", "jdks");
        List<InstalledJdk> jdks = new JdkRegistry(jdksRoot).list();
        if (jdks.isEmpty()) {
            System.out.println("(no JDKs installed under " + jdksRoot + ")");
            return 0;
        }
        int width = jdks.stream().mapToInt(j -> j.identifier().length()).max().orElse(0);
        for (InstalledJdk jdk : jdks) {
            System.out.printf("%-" + width + "s  %s%n",
                    jdk.identifier(), sourceLabel(jdk));
        }
        return 0;
    }

    private static String sourceLabel(InstalledJdk jdk) {
        Path home = jdk.home();
        if (!Files.isSymbolicLink(home)) {
            return "downloaded  " + home;
        }
        if (SymlinkProvisioner.isBrokenLink(home)) {
            return "broken      → " + readlinkOr(home);
        }
        return "linked      → " + readlinkOr(home);
    }

    private static String readlinkOr(Path link) {
        try { return Files.readSymbolicLink(link).toString(); }
        catch (IOException e) { return "?"; }
    }
}
