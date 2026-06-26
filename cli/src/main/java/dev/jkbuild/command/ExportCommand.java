// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import java.util.List;

/**
 * {@code jk export} parent — translate a jk project (and workspace) into a
 * runnable build for another tool (PRD §24.4):
 *
 * <ul>
 *   <li>{@code jk export gradle} — Gradle Kotlin-DSL build</li>
 *   <li>{@code jk export maven} (alias {@code pom}) — Maven {@code pom.xml} reactor</li>
 *   <li>{@code jk export idea} — IntelliJ IDEA project files (alias of {@code jk idea})</li>
 * </ul>
 *
 * <p>Replaces the former {@code jk export <file>} form — export now selects a
 * target <em>system</em>, not a filename.
 */
public final class ExportCommand implements CliCommand {

    @Override
    public String name() {
        return "export";
    }

    @Override
    public String description() {
        return "Export a jk project to Gradle, Maven, or IntelliJ IDEA";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new ExportGradleCommand(), new ExportMavenCommand(), new ExportIdeaCommand());
    }

    @Override
    public int run(Invocation in) {
        System.err.println("usage: jk export <gradle|maven|idea>");
        return 64;
    }
}
