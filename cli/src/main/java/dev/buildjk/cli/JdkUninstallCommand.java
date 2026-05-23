// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.jdk.JdkRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** {@code jk jdk uninstall <identifier>} — remove the JDK from {@code ~/.jk/jdks/}. */
@Command(name = "uninstall", description = "Uninstall JDK versions")
public final class JdkUninstallCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<identifier>",
            description = "Installed JDK identifier (e.g. 21.0.5-tem-x64-linux).")
    String identifier;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: ~/.jk/jdks.")
    Path jdksDir;

    @Override
    public Integer call() throws Exception {
        Path jdksRoot = jdksDir != null
                ? jdksDir : Path.of(System.getProperty("user.home"), ".jk", "jdks");
        JdkRegistry registry = new JdkRegistry(jdksRoot);
        if (!registry.remove(identifier)) {
            System.err.println("jk jdk uninstall: no installed JDK matches `" + identifier + "`");
            return 1;
        }
        System.out.println("Removed " + identifier);
        return 0;
    }
}
