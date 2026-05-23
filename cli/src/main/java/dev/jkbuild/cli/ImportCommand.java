// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.compat.JkBuildRenderer;
import dev.jkbuild.compat.ImportReport;
import dev.jkbuild.gradle.GradleImporter;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.mvn.PomImporter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code jk import <file>} — convert a Maven or Gradle build to {@code jk.toml}
 * (PRD §24.2 / §24.3). Dispatches by filename:
 * {@code pom.xml} → {@link PomImporter} (with multi-module → workspace);
 * {@code build.gradle(.kts)} → {@link GradleImporter} (best-effort declarative).
 *
 * <p>Always writes a {@code jk.toml} and a {@code jk-import-report.md} so
 * the user has the full list of constructs that didn't carry over cleanly.
 */
@Command(name = "import", description = "Convert a Maven or Gradle build to jk.toml")
public final class ImportCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<file>",
            description = "Source file: pom.xml, build.gradle, or build.gradle.kts.")
    Path source;

    @Option(names = "--out", description = "Path to write jk.toml. Default: <source-dir>/jk.toml.")
    Path out;

    @Option(names = "--report",
            description = "Path to write the import report. Default: <source-dir>/jk-import-report.md.")
    Path reportPath;

    @Option(names = "--force", description = "Overwrite existing jk.toml.")
    boolean force;

    @Override
    public Integer call() throws IOException {
        if (!Files.exists(source)) {
            System.err.println("jk import: source not found: " + source);
            return 66; // EX_NOINPUT
        }

        String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
        Path projectDir = source.toAbsolutePath().getParent();
        Path target = out != null ? out : projectDir.resolve("jk.toml");
        Path reportTarget = reportPath != null ? reportPath : projectDir.resolve("jk-import-report.md");

        if (Files.exists(target) && !force) {
            System.err.println("jk import: refusing to overwrite existing " + target
                    + " (use --force to replace).");
            return 73; // EX_CANTCREAT
        }

        Importable importable;
        if (filename.endsWith("pom.xml")) {
            PomImporter.WorkspaceImportResult result = PomImporter.importWorkspace(source);
            Map<String, JkBuild> members = new LinkedHashMap<>(result.members());
            importable = new Importable(result.root(), members, result.report());
        } else if (filename.equals("build.gradle") || filename.equals("build.gradle.kts")) {
            GradleImporter.Result result = GradleImporter.importFrom(source);
            importable = new Importable(result.jkBuild(), Map.of(), result.report());
        } else {
            System.err.println("jk import: unrecognised source — expected pom.xml, build.gradle, "
                    + "or build.gradle.kts (got " + source.getFileName() + ").");
            return 64; // EX_USAGE
        }

        Files.writeString(target, JkBuildRenderer.render(importable.root), StandardCharsets.UTF_8);
        System.out.println("Wrote " + target);

        for (var entry : importable.members.entrySet()) {
            Path memberJkBuild = projectDir.resolve(entry.getKey()).resolve("jk.toml");
            if (Files.exists(memberJkBuild) && !force) {
                System.err.println("jk import: refusing to overwrite existing " + memberJkBuild
                        + " (use --force to replace).");
                return 73;
            }
            Files.writeString(memberJkBuild,
                    JkBuildRenderer.render(entry.getValue()), StandardCharsets.UTF_8);
            System.out.println("Wrote " + memberJkBuild);
        }

        Files.writeString(reportTarget,
                importable.report.renderMarkdown(source.toString()),
                StandardCharsets.UTF_8);
        System.out.println("Wrote " + reportTarget);
        if (!importable.report.isEmpty()) {
            System.out.println("Import notes: " + importable.report.issues().size()
                    + (importable.report.hasErrors() ? " (includes Tier 3 errors)" : ""));
        }
        return 0;
    }

    private record Importable(JkBuild root, Map<String, JkBuild> members, ImportReport report) {}
}
