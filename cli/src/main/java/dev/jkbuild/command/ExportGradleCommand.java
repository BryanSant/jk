// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.gradle.GradleExporter;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code jk export gradle} — translate {@code jk.toml} (+ {@code jk.lock}) into a runnable Gradle
 * Kotlin-DSL build ({@code settings.gradle.kts} + {@code build.gradle.kts} per project). Locked
 * versions reproduce what jk builds; {@code project.jdk} maps to a Gradle toolchain + the foojay
 * resolver.
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
        return List.of(Opt.flag("Overwrite existing Gradle build files.", "--force"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        boolean force = in.isSet("force");
        ExportSupport.Loaded loaded = ExportSupport.load(global.workingDir(), "jk export gradle");
        if (loaded == null) return 66;

        GradleExporter.Result result = GradleExporter.export(
                loaded.root(), loaded.modulesByRelPath(), loaded.layoutByRelPath(), loaded.locked());

        // Pre-flight overwrite guard across every file we'd write.
        Path settings = loaded.rootDir().resolve("settings.gradle.kts");
        if (!ExportSupport.canWrite(settings, force, "jk export gradle")) return 73;
        for (String relDir : result.buildFiles().keySet()) {
            Path build = loaded.rootDir().resolve(relDir).resolve("build.gradle.kts");
            if (!ExportSupport.canWrite(build, force, "jk export gradle")) return 73;
        }

        Files.writeString(settings, result.settings(), StandardCharsets.UTF_8);
        ExportSupport.wrote(settings);
        for (Map.Entry<String, String> e : result.buildFiles().entrySet()) {
            Path build = loaded.rootDir().resolve(e.getKey()).resolve("build.gradle.kts");
            Files.createDirectories(build.getParent());
            Files.writeString(build, e.getValue(), StandardCharsets.UTF_8);
            ExportSupport.wrote(build);
        }

        int warnings = ExportSupport.printReport(result.report());
        if (warnings > 0) {
            System.out.println("  (" + warnings + " fidelity note" + (warnings == 1 ? "" : "s") + ")");
        }
        return 0;
    }
}
