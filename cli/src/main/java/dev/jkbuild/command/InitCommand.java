// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk init} — initialize a jk project in the current directory.
 * Delegates to {@link NewCommand} with directory pinned to {@code "."}.
 */
public final class InitCommand implements CliCommand {

    @Override public String name() { return "init"; }
    @Override public String description() { return "Initialize the current directory into a project (or member)"; }

    @Override public List<Opt> options() {
        // Same options as NewCommand (minus the positional directory parameter)
        return new NewCommand().options();
    }

    @Override
    public int run(Invocation in) throws IOException {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("jk.toml"))) {
            String cancel = Theme.colorize("⊘", Theme.active().warning().bold());
            System.err.println(cancel + " Failed to initialize a new project.");
            String coord = existingCoord(cwd.resolve("jk.toml"));
            System.err.println("The " + coord + " project already exists in this directory.");
            return 2;
        }

        // Delegate to NewCommand with directory pinned to "." so its target-
        // resolution, wizard-preset, and existing-project logic all fire
        // correctly against the current directory.
        NewCommand delegate = new NewCommand();
        delegate.name         = in.value("name").orElse(null);
        delegate.group        = in.value("group").orElse(null);
        delegate.jdk          = in.value("jdk").orElse(null);
        delegate.lang         = in.value("lang").orElse(null);
        delegate.executable   = in.flag("executable").orElse(null);
        delegate.shadow       = in.isSet("shadow");
        delegate.nativeImage  = in.isSet("native");
        delegate.depsCsv      = in.value("deps").orElse(null);
        delegate.layoutFlag   = in.value("layout").orElse(null);
        delegate.kotlinModule = in.value("kotlin-module").orElse(null);
        delegate.noMember     = in.isSet("no-member");
        delegate.directory    = Path.of(".");
        delegate.global       = GlobalOptions.from(in);
        return delegate.callBody();
    }

    private static String existingCoord(Path buildFile) {
        try {
            JkBuild project = JkBuildParser.parse(buildFile);
            String g = project.project().group();
            String a = project.project().name();
            return Theme.colorize(g, Theme.active().activeStep())
                    + ":" + Theme.colorize(a, Theme.active().activeStep().bold());
        } catch (Exception ignored) {
            return Theme.colorize("this project", Theme.active().activeStep().bold());
        }
    }
}
