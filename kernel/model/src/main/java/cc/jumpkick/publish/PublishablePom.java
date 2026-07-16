// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.publish;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.List;
import java.util.Objects;

/**
 * Renders a publish-grade {@code pom.xml} per PRD §21.2:
 *
 * <ul>
 *   <li>Coords + packaging ({@code jar}).
 *   <li>Standard scopes (compile / runtime / provided / test).
 *   <li>{@code <dependencyManagement>} for {@code PLATFORM} (BOM import).
 *   <li><b>No</b> {@code <repositories>}, {@code <build>}, {@code <profiles>}.
 *   <li>Optional {@code <name>}, {@code <description>}, {@code <url>}, {@code <licenses>}, {@code
 *       <developers>}, {@code <scm>}.
 *   <li>{@code PROCESSOR} deps are silently dropped — consumer-side compile-time only and not part
 *       of the artifact's surface.
 * </ul>
 *
 * <p>Companion to (but lighter than) {@code PomExporter} in {@code :compat}, which produces a
 * developer-facing POM that round-trips back through {@code jk import}.
 */
public final class PublishablePom {

    public record Pom(String xml) {}

    public record Metadata(
            String name, String description, String url, List<License> licenses, List<Developer> developers, Scm scm) {
        public Metadata {
            licenses = licenses == null ? List.of() : List.copyOf(licenses);
            developers = developers == null ? List.of() : List.copyOf(developers);
        }

        public static Metadata empty() {
            return new Metadata(null, null, null, List.of(), List.of(), null);
        }
    }

    public record License(String name, String url) {
        public License {
            Objects.requireNonNull(name, "name");
        }
    }

    public record Developer(String id, String name, String email) {
        public Developer {
            Objects.requireNonNull(id, "id");
        }
    }

    public record Scm(String url, String connection, String developerConnection) {}

    private PublishablePom() {}

    public static Pom render(JkBuild jkBuild, Metadata meta) {
        Objects.requireNonNull(jkBuild, "jkBuild");
        if (meta == null) meta = Metadata.empty();

        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ")
                .append("https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        sb.append("  <modelVersion>4.0.0</modelVersion>\n\n");

        JkBuild.Project p = jkBuild.project();
        sb.append("  <groupId>").append(escape(p.group())).append("</groupId>\n");
        sb.append("  <artifactId>").append(escape(p.name())).append("</artifactId>\n");
        sb.append("  <version>").append(escape(p.version())).append("</version>\n");
        sb.append("  <packaging>jar</packaging>\n");

        if (meta.name() != null)
            sb.append("  <name>").append(escape(meta.name())).append("</name>\n");
        // Metadata wins over project.description; fall back to the project's
        // description so `jk publish` carries the manifest's description into
        // the POM without forcing every caller to thread it through Metadata.
        String description = meta.description() != null ? meta.description() : p.description();
        if (description != null) {
            sb.append("  <description>").append(escape(description)).append("</description>\n");
        }
        if (meta.url() != null) sb.append("  <url>").append(escape(meta.url())).append("</url>\n");

        appendLicenses(sb, meta.licenses());
        appendDevelopers(sb, meta.developers());
        appendScm(sb, meta.scm());

        appendDependencyManagement(sb, jkBuild.dependencies().of(Scope.PLATFORM));
        appendDependencies(sb, jkBuild);

        sb.append("</project>\n");
        return new Pom(sb.toString());
    }

    private static void appendLicenses(StringBuilder sb, List<License> licenses) {
        if (licenses.isEmpty()) return;
        sb.append("  <licenses>\n");
        for (License l : licenses) {
            sb.append("    <license>\n");
            sb.append("      <name>").append(escape(l.name())).append("</name>\n");
            if (l.url() != null) {
                sb.append("      <url>").append(escape(l.url())).append("</url>\n");
            }
            sb.append("    </license>\n");
        }
        sb.append("  </licenses>\n");
    }

    private static void appendDevelopers(StringBuilder sb, List<Developer> developers) {
        if (developers.isEmpty()) return;
        sb.append("  <developers>\n");
        for (Developer d : developers) {
            sb.append("    <developer>\n");
            sb.append("      <id>").append(escape(d.id())).append("</id>\n");
            if (d.name() != null) {
                sb.append("      <name>").append(escape(d.name())).append("</name>\n");
            }
            if (d.email() != null) {
                sb.append("      <email>").append(escape(d.email())).append("</email>\n");
            }
            sb.append("    </developer>\n");
        }
        sb.append("  </developers>\n");
    }

    private static void appendScm(StringBuilder sb, Scm scm) {
        if (scm == null) return;
        sb.append("  <scm>\n");
        if (scm.url() != null) sb.append("    <url>").append(escape(scm.url())).append("</url>\n");
        if (scm.connection() != null) {
            sb.append("    <connection>").append(escape(scm.connection())).append("</connection>\n");
        }
        if (scm.developerConnection() != null) {
            sb.append("    <developerConnection>")
                    .append(escape(scm.developerConnection()))
                    .append("</developerConnection>\n");
        }
        sb.append("  </scm>\n");
    }

    private static void appendDependencyManagement(StringBuilder sb, List<Dependency> platforms) {
        if (platforms.isEmpty()) return;
        sb.append("  <dependencyManagement>\n    <dependencies>\n");
        for (Dependency d : platforms) {
            sb.append("      <dependency>\n");
            sb.append("        <groupId>").append(escape(d.group())).append("</groupId>\n");
            sb.append("        <artifactId>").append(escape(d.name())).append("</artifactId>\n");
            sb.append("        <version>")
                    .append(escape(versionOf(d.version())))
                    .append("</version>\n");
            sb.append("        <type>pom</type>\n");
            sb.append("        <scope>import</scope>\n");
            sb.append("      </dependency>\n");
        }
        sb.append("    </dependencies>\n  </dependencyManagement>\n");
    }

    private static void appendDependencies(StringBuilder sb, JkBuild jkBuild) {
        Scope[] order = {Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST};
        boolean any = false;
        for (Scope s : order) {
            if (!jkBuild.dependencies().of(s).isEmpty()) {
                any = true;
                break;
            }
        }
        if (!any) return;

        sb.append("  <dependencies>\n");
        for (Scope s : order) {
            String mavenScope = mavenScope(s);
            for (Dependency d : jkBuild.dependencies().of(s)) {
                // A branch-tracked git dep, even though it's locked in jk.lock, is still not a
                // stable reference for external consumers of the published artifact. `jk
                // publish` rejects it up front; skip here as a safety net so a stray caller
                // never emits a broken <version>=branch=...</version>.
                if (d.isGit() && d.gitSource().ref() instanceof GitRefSpec.Branch) {
                    continue;
                }
                sb.append("    <dependency>\n");
                sb.append("      <groupId>").append(escape(d.group())).append("</groupId>\n");
                sb.append("      <artifactId>").append(escape(d.name())).append("</artifactId>\n");
                sb.append("      <version>")
                        .append(escape(versionOf(d.version())))
                        .append("</version>\n");
                if (mavenScope != null) {
                    sb.append("      <scope>").append(mavenScope).append("</scope>\n");
                }
                sb.append("    </dependency>\n");
            }
        }
        sb.append("  </dependencies>\n");
    }

    private static String mavenScope(Scope s) {
        return switch (s) {
            case EXPORT, MAIN -> null; // compile scope (Maven default — transitive to consumers)
            case RUNTIME -> "runtime";
            case PROVIDED -> "provided";
            case TEST -> "test";
            case PLATFORM, PROCESSOR -> null;
            // Dev-loop scopes never publish: they are not in the `order` array above, and a
            // published POM must not leak development-only deps to consumers.
            case DEV, TEST_DEV -> null;
        };
    }

    private static String versionOf(VersionSelector v) {
        return switch (v) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> c.version();
            case VersionSelector.Tilde t -> t.version();
            case VersionSelector.Range r -> r.raw();
            case VersionSelector.Latest l -> "LATEST";
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
