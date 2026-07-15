// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.engine.protocol.GeneratedFiles;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import java.io.IOException;
import java.util.List;

/**
 * {@code jk export gradle} — translate {@code jk.toml} (+ {@code jk.lock}) into a runnable Gradle
 * Kotlin-DSL build ({@code settings.gradle.kts} + {@code build.gradle.kts} per project). Locked
 * versions reproduce what jk builds; {@code project.jdk} maps to a Gradle toolchain + the foojay
 * resolver.
 *
 * <p>Content generation runs engine-side (thin client); this command applies the overwrite guard,
 * writes the payloads, and prints the report.
 */
public final class ExportGradleCommand implements CliCommand {

    @Override
    public String name() {
        return "gradle";
    }

    @Override
    public String description() {
        return "Generate a Gradle (Kotlin DSL) build from jk.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("Overwrite existing Gradle build files.", "--overwrite"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        boolean force = in.isSet("overwrite");
        GeneratedFiles files = ExportSupport.generate(global.workingDir(), "export-gradle", "jk export gradle");
        if (files == null) return Exit.NO_INPUT;
        return ExportSupport.writeAll(files, force, "jk export gradle");
    }
}
