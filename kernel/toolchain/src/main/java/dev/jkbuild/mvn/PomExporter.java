// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.mvn;

import dev.jkbuild.compat.ImportReport;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;

import java.util.List;
import java.util.Map;

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

    /** Back-compat: export with auto layout and no locked versions (collapses selectors). */
    public static Result export(JkBuild jkBuild) {
        return export(jkBuild, JkBuild.Layout.AUTO, Map.of());
    }

    /** Back-compat: export with auto layout. */
    public static Result export(JkBuild jkBuild, Map<String, String> locked) {
        return export(jkBuild, JkBuild.Layout.AUTO, locked);
    }

    /**
     * Export {@code jkBuild} as a {@code pom.xml}. {@code locked} maps a
     * {@code group:artifact} module to the exact version resolved in {@code jk.lock};
     * when a module is present there it's emitted verbatim (the build reproduces
     * what jk builds), otherwise the declared selector collapses with a warning.
     *
     * <p>{@code layout} should be a concrete {@link JkBuild.Layout#SIMPLE} or
     * {@link JkBuild.Layout#TRADITIONAL} (callers resolve {@code AUTO} against the
     * directory tree); {@code SIMPLE} emits {@code <sourceDirectory>src</…>} +
     * {@code <testSourceDirectory>test</…>} so Maven finds jk's flat layout.
     * {@code TRADITIONAL}/{@code AUTO} use Maven's default {@code src/main/java}.
     */
    public static Result export(JkBuild jkBuild, JkBuild.Layout layout, Map<String, String> locked) {
        if (locked == null) locked = Map.of();
        if (layout == null) layout = JkBuild.Layout.AUTO;
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
        appendDependencyManagement(sb, jkBuild.dependencies().of(Scope.PLATFORM), locked, report);
        appendDependencies(sb, jkBuild.dependencies(), locked, report);
        appendBuild(sb, jkBuild, layout, locked, report);
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
                                                   Map<String, String> locked, ImportReport.Builder report) {
        if (platforms.isEmpty()) return;
        sb.append('\n');
        sb.append("  <dependencyManagement>\n");
        sb.append("    <dependencies>\n");
        for (Dependency d : platforms) {
            sb.append("      <dependency>\n");
            sb.append("        <groupId>").append(escape(d.group())).append("</groupId>\n");
            sb.append("        <artifactId>").append(escape(d.name())).append("</artifactId>\n");
            sb.append("        <version>").append(escape(resolveVersion(d, locked, report)))
                    .append("</version>\n");
            sb.append("        <type>pom</type>\n");
            sb.append("        <scope>import</scope>\n");
            sb.append("      </dependency>\n");
        }
        sb.append("    </dependencies>\n");
        sb.append("  </dependencyManagement>\n");
    }

    private static void appendDependencies(StringBuilder sb, JkBuild.Dependencies deps,
                                            Map<String, String> locked, ImportReport.Builder report) {
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
                appendDependency(sb, d, mavenScope(s), locked, report);
            }
        }
        sb.append("  </dependencies>\n");
    }

    private static void appendDependency(StringBuilder sb, Dependency d, String mavenScope,
                                         Map<String, String> locked, ImportReport.Builder report) {
        if (warnIfUnmappable(d, report)) return;
        sb.append("    <dependency>\n");
        sb.append("      <groupId>").append(escape(d.group())).append("</groupId>\n");
        sb.append("      <artifactId>").append(escape(d.name())).append("</artifactId>\n");
        sb.append("      <version>").append(escape(resolveVersion(d, locked, report)))
                .append("</version>\n");
        if (mavenScope != null) {
            sb.append("      <scope>").append(mavenScope).append("</scope>\n");
        }
        sb.append("    </dependency>\n");
    }

    /** Git / path / content-addressed deps have no Maven equivalent — warn and skip. */
    private static boolean warnIfUnmappable(Dependency d, ImportReport.Builder report) {
        if (d.isGit()) {
            report.warning("dependency `" + d.module() + "` is git-sourced; Maven has no git-source"
                    + " equivalent — dropped. Build & install that project to your local repo"
                    + " (`mvn install`, or `jk install`) so it resolves by coordinate, then add it"
                    + " as a normal <dependency>.");
            return true;
        }
        if (d.isPath()) {
            report.warning("dependency `" + d.module() + "` is a local path dep; Maven has no direct"
                    + " equivalent — dropped. Install the local project to your Maven repo first"
                    + " (`cd " + d.pathSource() + " && mvn install`, or `jk install`), then declare"
                    + " `" + d.module() + "` as a normal <dependency>; or fold it in as a Maven <module>.");
            return true;
        }
        if (d.isFile()) {
            report.warning("dependency `" + d.module() + "` is content-addressed (sha256); no Maven"
                    + " equivalent — dropped.");
            return true;
        }
        return false;
    }

    /** Locked exact version when known, else the collapsed selector (with a warning). */
    private static String resolveVersion(Dependency d, Map<String, String> locked,
                                         ImportReport.Builder report) {
        String fromLock = locked.get(d.module());
        if (fromLock != null && !fromLock.isBlank()) return fromLock;
        return extractVersion(d.version(), d.module(), report);
    }

    // Plugin coordinates baked into exported POMs — bump as upstreams release.
    private static final String TOOLCHAINS_PLUGIN = "4.5.0";   // org.mvnsearch:toolchains-maven-plugin (foojay)
    private static final String SHADE_PLUGIN      = "3.6.0";   // org.apache.maven.plugins:maven-shade-plugin
    private static final String NATIVE_PLUGIN     = "0.10.3";  // org.graalvm.buildtools:native-maven-plugin
    private static final String JAR_PLUGIN        = "3.4.1";   // org.apache.maven.plugins:maven-jar-plugin

    private static void appendBuild(StringBuilder sb, JkBuild jkBuild, JkBuild.Layout layout,
                                    Map<String, String> locked, ImportReport.Builder report) {
        JkBuild.Project p = jkBuild.project();
        List<Dependency> processors = jkBuild.dependencies().of(Scope.PROCESSOR);
        boolean kotlin     = p.kotlin() != null;
        boolean toolchain  = p.jdk() != null && !p.jdk().isBlank();
        boolean shade      = p.shadow();
        boolean nativeImg  = p.nativeMode() == JkBuild.NativeMode.ALWAYS;
        boolean jarManifest = (p.main() != null && !p.main().isBlank()) || !jkBuild.manifest().isEmpty();
        boolean anyPlugin  = !processors.isEmpty() || kotlin || toolchain || shade || nativeImg || jarManifest;
        boolean simple     = layout == JkBuild.Layout.SIMPLE;
        if (!anyPlugin && !simple) return;

        sb.append('\n').append("  <build>\n");
        // jk's simple layout (flat ./src + ./test) → Maven's source/test dirs.
        if (simple) {
            sb.append("    <sourceDirectory>src</sourceDirectory>\n");
            sb.append("    <testSourceDirectory>test</testSourceDirectory>\n");
            sb.append("    <resources><resource><directory>resources</directory></resource></resources>\n");
            sb.append("    <testResources><testResource><directory>test-resources</directory>")
                    .append("</testResource></testResources>\n");
        }
        if (anyPlugin) {
            sb.append("    <plugins>\n");
            if (kotlin)                appendKotlinPlugin(sb, p, report);
            if (!processors.isEmpty()) appendCompilerProcessorPlugin(sb, processors, p.javaRelease(), locked, report);
            if (toolchain)             appendToolchainsPlugin(sb, p);
            if (jarManifest)           appendJarPlugin(sb, p, jkBuild.manifest());
            if (shade)                 appendShadePlugin(sb);
            if (nativeImg)             appendNativePlugin(sb, jkBuild);
            sb.append("    </plugins>\n");
        }
        sb.append("  </build>\n");
    }

    private static void appendKotlinPlugin(StringBuilder sb, JkBuild.Project p, ImportReport.Builder report) {
        String ver = extractVersion(p.kotlin(), "kotlin", report);
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.jetbrains.kotlin</groupId>\n");
        sb.append("        <artifactId>kotlin-maven-plugin</artifactId>\n");
        sb.append("        <version>").append(escape(ver)).append("</version>\n");
        sb.append("        <executions>\n");
        sb.append("          <execution><id>compile</id><phase>compile</phase>")
                .append("<goals><goal>compile</goal></goals></execution>\n");
        sb.append("          <execution><id>test-compile</id><phase>test-compile</phase>")
                .append("<goals><goal>test-compile</goal></goals></execution>\n");
        sb.append("        </executions>\n");
        sb.append("      </plugin>\n");
    }

    private static void appendCompilerProcessorPlugin(StringBuilder sb, List<Dependency> processors,
                                                      int release, Map<String, String> locked,
                                                      ImportReport.Builder report) {
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
            sb.append("              <version>").append(escape(resolveVersion(d, locked, report)))
                    .append("</version>\n");
            sb.append("            </path>\n");
        }
        sb.append("          </annotationProcessorPaths>\n");
        sb.append("        </configuration>\n");
        sb.append("      </plugin>\n");
    }

    /**
     * Foojay-backed toolchains plugin — auto-downloads the project's JDK (the
     * Maven analog of Gradle's foojay-resolver), so `project.jdk` reproduces
     * jk's JDK auto-provisioning rather than requiring a hand-edited toolchains.xml.
     */
    private static void appendToolchainsPlugin(StringBuilder sb, JkBuild.Project p) {
        int major = p.jdkMajor();
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.mvnsearch</groupId>\n");
        sb.append("        <artifactId>toolchains-maven-plugin</artifactId>\n");
        sb.append("        <version>").append(TOOLCHAINS_PLUGIN).append("</version>\n");
        sb.append("        <executions><execution><goals><goal>toolchain</goal></goals></execution></executions>\n");
        sb.append("        <configuration>\n          <toolchains>\n            <jdk>\n");
        sb.append("              <version>").append(major > 0 ? major : p.javaRelease()).append("</version>\n");
        sb.append("            </jdk>\n          </toolchains>\n        </configuration>\n");
        sb.append("      </plugin>\n");
    }

    private static void appendJarPlugin(StringBuilder sb, JkBuild.Project p, Map<String, String> manifest) {
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.apache.maven.plugins</groupId>\n");
        sb.append("        <artifactId>maven-jar-plugin</artifactId>\n");
        sb.append("        <version>").append(JAR_PLUGIN).append("</version>\n");
        sb.append("        <configuration>\n          <archive>\n            <manifest>\n");
        if (p.main() != null && !p.main().isBlank()) {
            sb.append("              <mainClass>").append(escape(p.main())).append("</mainClass>\n");
        }
        if (!manifest.isEmpty()) {
            sb.append("            </manifest>\n            <manifestEntries>\n");
            for (Map.Entry<String, String> e : manifest.entrySet()) {
                sb.append("              <").append(escape(e.getKey())).append('>')
                        .append(escape(e.getValue())).append("</").append(escape(e.getKey())).append(">\n");
            }
            sb.append("            </manifestEntries>\n");
        } else {
            sb.append("            </manifest>\n");
        }
        sb.append("          </archive>\n        </configuration>\n");
        sb.append("      </plugin>\n");
    }

    private static void appendShadePlugin(StringBuilder sb) {
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.apache.maven.plugins</groupId>\n");
        sb.append("        <artifactId>maven-shade-plugin</artifactId>\n");
        sb.append("        <version>").append(SHADE_PLUGIN).append("</version>\n");
        sb.append("        <executions><execution><phase>package</phase>")
                .append("<goals><goal>shade</goal></goals></execution></executions>\n");
        sb.append("      </plugin>\n");
    }

    private static void appendNativePlugin(StringBuilder sb, JkBuild jkBuild) {
        JkBuild.Project p = jkBuild.project();
        String main = jkBuild.nativeConfig() != null && jkBuild.nativeConfig().mainClass() != null
                ? jkBuild.nativeConfig().mainClass() : p.main();
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.graalvm.buildtools</groupId>\n");
        sb.append("        <artifactId>native-maven-plugin</artifactId>\n");
        sb.append("        <version>").append(NATIVE_PLUGIN).append("</version>\n");
        sb.append("        <extensions>true</extensions>\n");
        if (main != null && !main.isBlank()) {
            sb.append("        <configuration><mainClass>").append(escape(main))
                    .append("</mainClass></configuration>\n");
        }
        sb.append("      </plugin>\n");
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
            case EXPORT, MAIN -> null;  // compile scope (Maven default — transitive to consumers)
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
