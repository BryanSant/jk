// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** {@code jk jdk uninstall <identifier>} — remove the JDK from the IntelliJ JDK directory. */
@Command(name = "uninstall", aliases = {"remove"},
        description = "Uninstall JDK versions")
public final class JdkUninstallCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<identifier>",
            description = "Installed JDK identifier (e.g. temurin-21.0.5).")
    String identifier;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Override
    public Integer call() throws Exception {
        Path jdksRoot = jdksDir != null ? jdksDir : IntellijJdkDir.root();
        JdkRegistry registry = new JdkRegistry(jdksRoot);
        if (!registry.remove(identifier)) {
            System.err.println("jk jdk uninstall: no installed JDK matches `" + identifier + "`");
            return 1;
        }
        System.out.println("Removed " + identifier);
        return 0;
    }
}
