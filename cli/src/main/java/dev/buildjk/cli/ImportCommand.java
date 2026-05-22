// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.compat.BuildJkRenderer;
import dev.buildjk.mvn.PomImporter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code jk import <file>} — convert a Maven or Gradle build to {@code build.jk}
 * (PRD §24.2 / §24.3). Slice C wires up the Maven side; Gradle imports land
 * in slice E.
 *
 * <p>The verb always writes two files: {@code build.jk} and
 * {@code jk-import-report.md}. The latter lists every construct the importer
 * could not carry over perfectly so the user can review the result.
 */
@Command(name = "import", description = "Convert a Maven or Gradle build to build.jk.")
public final class ImportCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<file>",
            description = "Source file: pom.xml, build.gradle, or build.gradle.kts.")
    Path source;

    @Option(names = "--out", description = "Path to write build.jk. Default: <source-dir>/build.jk.")
    Path out;

    @Option(names = "--report",
            description = "Path to write the import report. Default: <source-dir>/jk-import-report.md.")
    Path reportPath;

    @Option(names = "--force", description = "Overwrite existing build.jk.")
    boolean force;

    @Override
    public Integer call() throws IOException {
        if (!Files.exists(source)) {
            System.err.println("jk import: source not found: " + source);
            return 66; // EX_NOINPUT
        }

        String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lower.endsWith("pom.xml")) {
            if (lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts")) {
                System.err.println("jk import: Gradle import lands in v0.5 slice E. "
                        + "Use `jk import pom.xml` for now.");
            } else {
                System.err.println("jk import: unrecognised source — expected pom.xml, build.gradle, "
                        + "or build.gradle.kts (got " + source.getFileName() + ").");
            }
            return 64; // EX_USAGE
        }

        Path projectDir = source.toAbsolutePath().getParent();
        Path target = out != null ? out : projectDir.resolve("build.jk");
        Path reportTarget = reportPath != null ? reportPath : projectDir.resolve("jk-import-report.md");

        if (Files.exists(target) && !force) {
            System.err.println("jk import: refusing to overwrite existing " + target
                    + " (use --force to replace).");
            return 73; // EX_CANTCREAT
        }

        PomImporter.WorkspaceImportResult result = PomImporter.importWorkspace(source);
        Files.writeString(target, BuildJkRenderer.render(result.root()), StandardCharsets.UTF_8);
        System.out.println("Wrote " + target);

        for (var entry : result.members().entrySet()) {
            Path memberBuildJk = projectDir.resolve(entry.getKey()).resolve("build.jk");
            if (Files.exists(memberBuildJk) && !force) {
                System.err.println("jk import: refusing to overwrite existing " + memberBuildJk
                        + " (use --force to replace).");
                return 73;
            }
            Files.writeString(memberBuildJk,
                    BuildJkRenderer.render(entry.getValue()), StandardCharsets.UTF_8);
            System.out.println("Wrote " + memberBuildJk);
        }

        Files.writeString(reportTarget,
                result.report().renderMarkdown(source.toString()),
                StandardCharsets.UTF_8);
        System.out.println("Wrote " + reportTarget);
        if (!result.report().isEmpty()) {
            System.out.println("Import notes: " + result.report().issues().size()
                    + (result.report().hasErrors() ? " (includes Tier 3 errors)" : ""));
        }
        return 0;
    }
}
