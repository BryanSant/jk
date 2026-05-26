// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.resolver.DependencyTree;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** {@code jk tree} — print the resolved dependency tree. */
@Command(name = "tree", description = "Print the resolved dependency tree")
public final class TreeCommand implements Callable<Integer> {    @Option(names = "--depth",
            description = "Maximum tree depth. Default: unlimited.")
    Integer depth;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk tree: no jk.toml in " + dir);
            return 2;
        }
        if (!Files.exists(lockFile)) {
            System.err.println("jk tree: no jk.lock in " + dir + " (run `jk lock` first)");
            return 2;
        }

        JkBuild project = JkBuildParser.parse(buildFile);
        Lockfile lock = LockfileReader.read(lockFile);
        int max = depth != null ? depth : Integer.MAX_VALUE;

        // Header line matching the wizard's gradient title.
        System.out.println(Theme.gradientHeaderAnsi("Jk - Project Tree"));
        System.out.print(DependencyTree.render(project, lock, max, styling()));
        return 0;
    }

    /**
     * Color pattern matching how Java devs read a Maven coordinate:
     * <pre>
     *   <blue>group</blue>:<b><cyan>artifact</cyan></b>:<yellow>version</yellow>
     * </pre>
     * Rails get the same dim dark-gray the wizard uses for its
     * settled rails. {@link Theme#colorize} respects {@code --color}
     * / {@code NO_COLOR} / dumb terminals, so escapes are dropped
     * cleanly when color is off.
     */
    private static DependencyTree.Styling styling() {
        return new DependencyTree.Styling(
                s -> Theme.colorize(s, Theme.darkGray()),
                s -> Theme.colorize(s, Theme.blue()),
                s -> Theme.colorize(s, Theme.activeStep().bold()),
                s -> Theme.colorize(s, Theme.warning()));
    }
}
