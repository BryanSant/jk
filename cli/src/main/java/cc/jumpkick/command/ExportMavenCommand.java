// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.engine.protocol.GeneratedFiles;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk export maven} — translate {@code jk.toml} (+ {@code jk.lock}) into a runnable Maven
 * build: a {@code pom.xml} for a single project, or a {@code <packaging>pom</packaging>} reactor
 * root plus a child {@code pom.xml} per workspace module. JDK toolchains map to {@code
 * maven.compiler.release} + the foojay-backed {@code toolchains-maven-plugin}.
 *
 * <p>Content generation runs engine-side (thin client — it needs the parsed root, every workspace
 * module, and merged locked versions); this command applies the overwrite guard, writes the
 * payloads, and prints the report.
 */
public final class ExportMavenCommand implements CliCommand {

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public List<String> aliases() {
        return List.of("pom");
    }

    @Override
    public String description() {
        return "Generate a Maven pom.xml from jk.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("Overwrite existing pom.xml files.", "--overwrite"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        boolean force = in.isSet("overwrite");
        GeneratedFiles files = ExportSupport.generate(global.workingDir(), "export-maven", "jk export maven");
        if (files == null) return Exit.NO_INPUT;
        return ExportSupport.writeAll(files, force, "jk export maven");
    }
}
