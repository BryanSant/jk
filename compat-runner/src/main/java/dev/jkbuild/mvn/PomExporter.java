// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.mvn;

import dev.jkbuild.compat.ImportReport;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;

import java.util.List;

/**
 * Renders a {@link JkBuild} as a Maven-Central-grade {@code pom.xml} (PRD
 * §21.3 / §24.4). The companion to {@link PomImporter}.
 *
 * <p>Fidelity rules per PRD §21.3:
 * <ul>
 *   <li>{@code dependencies.platform} → {@code <dependencyManagement>} with
 *       {@code <scope>import</scope><type>pom</type>}.</li>
 *   <li>{@code dependencies.provided} → {@code <scope>provided</scope>}.</li>
 *   <li>{@code dependencies.processor} → {@code annotationProcessorPaths}
 *       inside the {@code maven-compiler-plugin} configuration.</li>
 *   <li>{@code workspace} root → {@code <packaging>pom</packaging>} plus
 *       {@code <modules>}.</li>
 *   <li>Features are stripped (consumer-side selectors, not a POM concept).</li>
 *   <li>jk profiles are not emitted — the model is too different from
 *       Maven's; a publish-time warning lands the caveat.</li>
 *   <li>Non-exact version selectors collapse to their inner version string
 *       with a fidelity warning in the {@link ImportReport}.</li>
 * </ul>
 */
public final class PomExporter {

    public record Result(String xml, ImportReport report) {}

    private PomExporter() {}

    public static Result export(JkBuild jkBuild) {
        ImportReport.Builder report = ImportReport.builder();
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ")
                .append("https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        sb.append("  <modelVersion>4.0.0</modelVersion>\n\n");

        JkBuild.Project p = jkBuild.project();
        appendCoords(sb, p, jkBuild.isWorkspaceRoot());
        appendProperties(sb, p);
        appendModules(sb, jkBuild);
        appendRepositories(sb, jkBuild.repositories());
        appendDependencyManagement(sb, jkBuild.dependencies().of(Scope.PLATFORM), report);
        appendDependencies(sb, jkBuild.dependencies(), report);
        appendProcessorPlugin(sb, jkBuild.dependencies().of(Scope.PROCESSOR), p.javaRelease(), report);
        warnAboutDroppedConcerns(jkBuild, report);

        sb.append("</project>\n");
        return new Result(sb.toString(), report.build());
    }

    private static void appendCoords(StringBuilder sb, JkBuild.Project p, boolean workspaceRoot) {
        sb.append("  <groupId>").append(escape(p.group())).append("</groupId>\n");
        sb.append("  <artifactId>").append(escape(p.name())).append("</artifactId>\n");
        sb.append("  <version>").append(escape(p.version())).append("</version>\n");
        sb.append("  <packaging>").append(workspaceRoot ? "pom" : "jar").append("</packaging>\n");
        if (p.description() != null) {
            sb.append("  <description>").append(escape(p.description())).append("</description>\n");
        }
    }

    private static void appendProperties(StringBuilder sb, JkBuild.Project p) {
        sb.append('\n');
        sb.append("  <properties>\n");
        sb.append("    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        sb.append("    <maven.compiler.release>").append(p.javaRelease())
                .append("</maven.compiler.release>\n");
        sb.append("  </properties>\n");
    }

    private static void appendModules(StringBuilder sb, JkBuild jkBuild) {
        if (!jkBuild.isWorkspaceRoot()) return;
        sb.append('\n');
        sb.append("  <modules>\n");
        for (String member : jkBuild.workspace().members()) {
            sb.append("    <module>").append(escape(member)).append("</module>\n");
        }
        sb.append("  </modules>\n");
    }

    private static void appendRepositories(StringBuilder sb, List<RepositorySpec> repos) {
        if (repos.isEmpty()) return;
        sb.append('\n');
        sb.append("  <repositories>\n");
        for (RepositorySpec r : repos) {
            sb.append("    <repository>\n");
            sb.append("      <id>").append(escape(r.name())).append("</id>\n");
            sb.append("      <url>").append(escape(r.url().toString())).append("</url>\n");
            sb.append("    </repository>\n");
        }
        sb.append("  </repositories>\n");
    }

    private static void appendDependencyManagement(StringBuilder sb, List<Dependency> platforms,
                                                   ImportReport.Builder report) {
        if (platforms.isEmpty()) return;
        sb.append('\n');
        sb.append("  <dependencyManagement>\n");
        sb.append("    <dependencies>\n");
        for (Dependency d : platforms) {
            sb.append("      <dependency>\n");
            sb.append("        <groupId>").append(escape(d.group())).append("</groupId>\n");
            sb.append("        <artifactId>").append(escape(d.name())).append("</artifactId>\n");
            sb.append("        <version>").append(escape(extractVersion(d.version(), d.module(), report)))
                    .append("</version>\n");
            sb.append("        <type>pom</type>\n");
            sb.append("        <scope>import</scope>\n");
            sb.append("      </dependency>\n");
        }
        sb.append("    </dependencies>\n");
        sb.append("  </dependencyManagement>\n");
    }

    private static void appendDependencies(StringBuilder sb, JkBuild.Dependencies deps,
                                            ImportReport.Builder report) {
        // Maven emits compile / runtime / provided / test under <dependencies>;
        // platform and processor are handled elsewhere.
        Scope[] order = {Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST};
        boolean any = false;
        for (Scope s : order) {
            if (!deps.of(s).isEmpty()) { any = true; break; }
        }
        if (!any) return;

        sb.append('\n');
        sb.append("  <dependencies>\n");
        for (Scope s : order) {
            for (Dependency d : deps.of(s)) {
                appendDependency(sb, d, mavenScope(s), report);
            }
        }
        sb.append("  </dependencies>\n");
    }

    private static void appendDependency(StringBuilder sb, Dependency d, String mavenScope,
                                         ImportReport.Builder report) {
        sb.append("    <dependency>\n");
        sb.append("      <groupId>").append(escape(d.group())).append("</groupId>\n");
        sb.append("      <artifactId>").append(escape(d.name())).append("</artifactId>\n");
        sb.append("      <version>").append(escape(extractVersion(d.version(), d.module(), report)))
                .append("</version>\n");
        if (mavenScope != null) {
            sb.append("      <scope>").append(mavenScope).append("</scope>\n");
        }
        sb.append("    </dependency>\n");
    }

    private static void appendProcessorPlugin(StringBuilder sb, List<Dependency> processors,
                                              int release, ImportReport.Builder report) {
        if (processors.isEmpty()) return;
        sb.append('\n');
        sb.append("  <build>\n");
        sb.append("    <plugins>\n");
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.apache.maven.plugins</groupId>\n");
        sb.append("        <artifactId>maven-compiler-plugin</artifactId>\n");
        sb.append("        <configuration>\n");
        sb.append("          <release>").append(release).append("</release>\n");
        sb.append("          <annotationProcessorPaths>\n");
        for (Dependency d : processors) {
            sb.append("            <path>\n");
            sb.append("              <groupId>").append(escape(d.group())).append("</groupId>\n");
            sb.append("              <artifactId>").append(escape(d.name())).append("</artifactId>\n");
            sb.append("              <version>")
                    .append(escape(extractVersion(d.version(), d.module(), report)))
                    .append("</version>\n");
            sb.append("            </path>\n");
        }
        sb.append("          </annotationProcessorPaths>\n");
        sb.append("        </configuration>\n");
        sb.append("      </plugin>\n");
        sb.append("    </plugins>\n");
        sb.append("  </build>\n");
    }

    private static void warnAboutDroppedConcerns(JkBuild jkBuild, ImportReport.Builder report) {
        if (!jkBuild.features().isEmpty()) {
            report.warning("`features` block dropped — features are jk-only consumer-side"
                    + " selectors; not a POM concept (PRD §21.3).");
        }
        if (jkBuild.profiles() != null && !jkBuild.profiles().byName().isEmpty()) {
            report.warning("`profiles` block dropped — jk profiles change javac/jvm args at the"
                    + " consumer side, which doesn't translate to Maven profiles.");
        }
    }

    private static String mavenScope(Scope s) {
        return switch (s) {
            case MAIN -> null;          // Maven default == compile
            case RUNTIME -> "runtime";
            case PROVIDED -> "provided";
            case TEST -> "test";
            case PLATFORM, PROCESSOR -> null; // emitted elsewhere
        };
    }

    /**
     * Extract a Maven-compatible version string from a {@link VersionSelector}.
     * Maven publishes exact pins; non-exact selectors collapse to their version
     * with a fidelity warning.
     */
    private static String extractVersion(VersionSelector v, String module, ImportReport.Builder report) {
        return switch (v) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> {
                report.warning("dependency `" + module + "` was declared as `^" + c.version()
                        + "` (caret); pinned to `" + c.version() + "` in the exported POM."
                        + " Consumers using Maven get exact-match semantics.");
                yield c.version();
            }
            case VersionSelector.Tilde t -> {
                report.warning("dependency `" + module + "` was declared as `~" + t.version()
                        + "` (tilde); pinned to `" + t.version() + "` in the exported POM.");
                yield t.version();
            }
            case VersionSelector.Range r -> {
                // jk range expressions usually look enough like Maven ranges to pass through,
                // but emit a warning so the user reviews.
                report.warning("dependency `" + module + "` uses range `" + r.raw()
                        + "`; emitted verbatim — Maven range syntax differs in edge cases.");
                yield r.raw();
            }
            case VersionSelector.Latest l -> {
                report.warning("dependency `" + module + "` uses `latest`; emitted as `LATEST` —"
                        + " note that Maven 3+ has deprecated LATEST/RELEASE in <version>.");
                yield "LATEST";
            }
        };
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
