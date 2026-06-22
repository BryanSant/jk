// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.compat.ImportReport;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.mvn.PomExporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code jk export maven} — translate {@code jk.toml} (+ {@code jk.lock}) into a
 * runnable Maven build: a {@code pom.xml} for a single project, or a
 * {@code <packaging>pom</packaging>} reactor root plus a child {@code pom.xml}
 * per workspace module. JDK toolchains map to {@code maven.compiler.release} +
 * the foojay-backed {@code toolchains-maven-plugin}.
 */
public final class ExportMavenCommand implements CliCommand {

    @Override public String name() { return "maven"; }
    @Override public List<String> aliases() { return List.of("pom"); }
    @Override public String description() { return "Generate a Maven pom.xml from jk.toml"; }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("Overwrite existing pom.xml files.", "--force"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        boolean force = in.isSet("force");
        ExportSupport.Loaded loaded = ExportSupport.load(global.workingDir(), "jk export maven");
        if (loaded == null) return 66;

        // Pre-flight overwrite guard: root + every module pom.
        Path rootPom = loaded.rootDir().resolve("pom.xml");
        if (!ExportSupport.canWrite(rootPom, force, "jk export maven")) return 73;
        for (Path moduleDir : loaded.modules().keySet()) {
            if (!ExportSupport.canWrite(moduleDir.resolve("pom.xml"), force, "jk export maven")) return 73;
        }

        ImportReport.Builder combined = ImportReport.builder();

        PomExporter.Result rootResult = PomExporter.export(loaded.root(),
                ExportSupport.resolveLayout(loaded.rootDir(), loaded.root()), loaded.locked());
        Files.writeString(rootPom, rootResult.xml(), StandardCharsets.UTF_8);
        ExportSupport.wrote(rootPom);
        merge(combined, rootResult.report());

        for (Map.Entry<Path, JkBuild> e : loaded.modules().entrySet()) {
            Map<String, String> moduleLocked = ExportSupport.lockedVersions(e.getKey());
            PomExporter.Result r = PomExporter.export(e.getValue(),
                    ExportSupport.resolveLayout(e.getKey(), e.getValue()),
                    moduleLocked.isEmpty() ? loaded.locked() : moduleLocked);
            Path pom = e.getKey().resolve("pom.xml");
            Files.writeString(pom, r.xml(), StandardCharsets.UTF_8);
            ExportSupport.wrote(pom);
            merge(combined, r.report());
        }

        int warnings = ExportSupport.printReport(combined.build());
        if (warnings > 0) {
            System.out.println("  (" + warnings + " fidelity note" + (warnings == 1 ? "" : "s") + ")");
        }
        return 0;
    }

    private static void merge(ImportReport.Builder into, ImportReport from) {
        for (ImportReport.Issue issue : from.issues()) {
            if (issue.severity() == ImportReport.Severity.ERROR) into.error(issue.message());
            else into.warning(issue.message());
        }
    }
}
