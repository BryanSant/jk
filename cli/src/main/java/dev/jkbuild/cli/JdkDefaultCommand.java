// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkRegistry;
import org.jline.utils.AttributedStyle;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk default <spec>} — pick an already-installed JDK matching
 * {@code <spec>} and make it the system-wide default ({@code JAVA_HOME}
 * source). The spec parser is the same one {@code jk jdk install} uses
 * (see {@link dev.jkbuild.jdk.JdkSelector#parseFlexible}), so inputs like
 * {@code 25}, {@code temurin-25}, {@code corretto-25.0.3}, and
 * {@code 26.0.1-librca} all resolve.
 *
 * <p>Resolution walks the JDK registry in probe-chain order and returns
 * the first install satisfying every spec constraint — the "natural
 * find-the-first-one" precedence the user expects.
 */
@Command(name = "default", description = "Set an installed JDK as the system default")
public final class JdkDefaultCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<spec>",
            description = "JDK spec to resolve (e.g. 25, temurin-25, corretto-25.0.3).")
    String spec;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        Optional<InstalledJdk> match = registry.findBySpec(spec);
        if (match.isEmpty()) {
            System.err.println("jk jdk default: no installed JDK matches `" + spec
                    + "` (try `jk jdk list` to see what's available, or "
                    + "`jk jdk install " + spec + "` to install one)");
            return 1;
        }

        InstalledJdk chosen = match.get();
        GlobalDefaultJdk.current().set(chosen);
        System.out.println(Theme.colorize("➜", Theme.brightGreen())
                + " " + Theme.colorize(chosen.identifier(), AttributedStyle.DEFAULT.bold())
                + " is now the " + Theme.colorize("default", Theme.focused())
                + " JDK");
        return 0;
    }
}
