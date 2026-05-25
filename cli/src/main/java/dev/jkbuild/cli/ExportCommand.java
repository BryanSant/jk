// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.mvn.PomExporter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code jk export <file>} — emit a Maven-Central-grade {@code pom.xml} from
 * {@code jk.toml} (PRD §24.4). Required by v0.6 publishing.
 *
 * <p>v0.5 ships POM export. {@code build.gradle.kts} export lands at v1.1+
 * per PRD §24.4; until then the verb refuses Gradle targets with a clear
 * message.
 */
@Command(name = "export", description = "Emit a Maven pom.xml from jk.toml")
public final class ExportCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<file>",
            description = "Target file: pom.xml (Gradle export lands at v1.1+).")
    Path target;    @Option(names = "--force", description = "Overwrite an existing pom.xml.")
    boolean force;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        String filename = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.equals("pom.xml")) {
            if (filename.startsWith("build.gradle")) {
                System.err.println("jk export: Gradle export is v1.1+ per PRD §24.4. "
                        + "Use `jk export pom.xml` for now.");
            } else {
                System.err.println("jk export: unrecognised target — expected `pom.xml` (got "
                        + target.getFileName() + ").");
            }
            return 64; // EX_USAGE
        }

        Path projectDir = global.workingDir();
        Path jkBuildPath = projectDir.resolve("jk.toml");
        if (!Files.exists(jkBuildPath)) {
            System.err.println("jk export: " + jkBuildPath + " not found.");
            return 66; // EX_NOINPUT
        }

        Path effectiveTarget = target.isAbsolute() ? target : projectDir.resolve(target);
        if (Files.exists(effectiveTarget) && !force) {
            System.err.println("jk export: refusing to overwrite existing " + effectiveTarget
                    + " (use --force to replace).");
            return 73; // EX_CANTCREAT
        }

        JkBuild root = JkBuildParser.parse(jkBuildPath);
        PomExporter.Result rootResult = PomExporter.export(root);
        Files.writeString(effectiveTarget, rootResult.xml(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + effectiveTarget);

        int totalWarnings = rootResult.report().issues().size();
        if (root.isWorkspaceRoot()) {
            Map<Path, JkBuild> members = WorkspaceLoader.loadMembers(projectDir, root);
            for (Map.Entry<Path, JkBuild> e : members.entrySet()) {
                Path memberPom = e.getKey().resolve("pom.xml");
                if (Files.exists(memberPom) && !force) {
                    System.err.println("jk export: refusing to overwrite existing " + memberPom
                            + " (use --force to replace).");
                    return 73;
                }
                PomExporter.Result memberResult = PomExporter.export(e.getValue());
                Files.writeString(memberPom, memberResult.xml(), StandardCharsets.UTF_8);
                System.out.println("Wrote " + memberPom);
                totalWarnings += memberResult.report().issues().size();
            }
        }

        if (totalWarnings > 0) {
            System.out.println("Export notes: " + totalWarnings
                    + " fidelity warning" + (totalWarnings == 1 ? "" : "s")
                    + " — see PRD §21.3 for what doesn't round-trip.");
        }
        return 0;
    }
}
