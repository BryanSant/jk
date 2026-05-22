// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.jdk.InstalledJdk;
import dev.buildjk.jdk.JdkRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** {@code jk jdk list} — print every JDK present under {@code ~/.jk/jdks/}. */
@Command(name = "list", description = "List installed JDKs.")
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
        for (InstalledJdk jdk : jdks) {
            System.out.println(jdk.identifier() + "    " + jdk.home());
        }
        return 0;
    }
}
