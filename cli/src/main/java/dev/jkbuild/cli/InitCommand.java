// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk init} — initialize a jk project in the current directory.
 *
 * <p>Like {@code cargo init}: scaffolds {@code jk.toml}, {@code jk.lock},
 * and source stubs into the <em>current</em> directory rather than a new
 * sub-directory. Errors immediately when a {@code jk.toml} already exists.
 * All wizard and flag options are identical to {@code jk new}.
 */
@Command(name = "init", description = "Initialize a jk project in the current directory")
public final class InitCommand implements Callable<Integer> {

    @Option(names = "--name", description = "Project name (artifact). Defaults to the current directory name.")
    String name;

    @Option(names = "--artifact", description = "Maven artifactId. Defaults to --name.")
    String artifact;

    @Option(names = "--group", description = "Maven groupId. Default: inferred from ~/.gitconfig, else 'com.example'.")
    String group;

    @Option(names = "--jdk", description = "JDK major version. Default: '25'.")
    String jdk;

    @Option(names = "--lang", description = "Source language: java | kotlin. Default: java.")
    String lang;

    @Option(names = "--executable", description = "Generate an executable project (default is library).", negatable = true)
    Boolean executable;

    @Option(names = "--shadow", description = "Bundle as a shadow (fat) jar. Implies --executable.")
    boolean shadow;

    @Option(names = "--native", description = "Wire a GraalVM native-image build.")
    boolean nativeImage;

    @Option(names = "--deps", description = "Comma-separated curated deps: lombok, jspecify, kotest, commons-lang, commons-io, guava.")
    String depsCsv;

    @Option(names = "--kotlin-compact", description = "Use Kotlin compact project structure (Main.kt at ./src/Main.kt).")
    boolean kotlinCompact;

    @Option(names = "--kotlin-module", description = "Kotlin module name; emitted as project.module in jk.toml.")
    String kotlinModule;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("jk.toml"))) {
            String cancel = Theme.colorize("⊘", Theme.warning().bold());
            System.err.println(cancel + " Failed to initialize a new project.");
            String coord = existingCoord(cwd.resolve("jk.toml"));
            System.err.println("The " + coord + " project already exists in this directory.");
            return 2;
        }

        // Delegate to NewCommand with directory pinned to "." so its target-
        // resolution, wizard-preset, and existing-project logic all fire
        // correctly against the current directory.
        NewCommand delegate = new NewCommand();
        delegate.directory    = Path.of(".");
        delegate.name         = this.name;
        delegate.artifact     = this.artifact;
        delegate.group        = this.group;
        delegate.jdk          = this.jdk;
        delegate.lang         = this.lang;
        delegate.executable   = this.executable;
        delegate.shadow       = this.shadow;
        delegate.nativeImage  = this.nativeImage;
        delegate.depsCsv      = this.depsCsv;
        delegate.kotlinCompact = this.kotlinCompact;
        delegate.kotlinModule = this.kotlinModule;
        delegate.global       = this.global;
        return delegate.call();
    }

    private static String existingCoord(Path buildFile) {
        try {
            JkBuild project = JkBuildParser.parse(buildFile);
            String g = project.project().group();
            String a = project.project().artifact();
            return Theme.colorize(g, Theme.activeStep())
                    + ":" + Theme.colorize(a, Theme.activeStep().bold());
        } catch (Exception ignored) {
            return Theme.colorize("this project", Theme.activeStep().bold());
        }
    }
}
