// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk pin <spec>} — pin the project to an installed JDK by
 * writing {@code .jdk-version}. The pin string is the directory name
 * under the IntelliJ JDK directory (e.g. {@code temurin-21.0.5}).
 */
@Command(name = "pin", description = "Pin to a specific JDK version")
public final class JdkPinCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<spec>",
            description = "Installed JDK identifier or prefix (e.g. temurin-21).")
    String spec;    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path projectDir = global.workingDir();
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        Optional<InstalledJdk> jdk = registry.find(spec)
                .or(() -> {
                    try { return registry.findByPrefix(spec); }
                    catch (IOException e) { throw new RuntimeException(e); }
                });
        if (jdk.isEmpty()) {
            System.err.println("jk jdk pin: no installed JDK matches `" + spec
                    + "` (try `jk jdk list`)");
            return 1;
        }

        String identifier = jdk.get().identifier();
        Files.writeString(projectDir.resolve(".jdk-version"),
                identifier + "\n", StandardCharsets.UTF_8);
        dev.jkbuild.jdk.JdkAccessLedger.atDefaultPath().touch(identifier, "pin");
        System.out.println("Pinned project to " + identifier);
        return 0;
    }
}
