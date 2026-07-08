// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.gradle;

import dev.jkbuild.compat.ImportReport;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link JkBuild} (and workspace) as a Gradle Kotlin-DSL build: {@code
 * settings.gradle.kts} plus a {@code build.gradle.kts} per project. The export-direction companion
 * to {@link GradleImporter}.
 *
 * <p>Versions come from {@code jk.lock} when present ({@code locked} maps {@code group:artifact} →
 * exact version), so the generated build reproduces what jk builds; otherwise the declared selector
 * collapses with a warning. {@code project.jdk} maps to a Gradle Java toolchain, with the {@code
 * foojay-resolver-convention} settings plugin enabling auto-download.
 */
public final class GradleExporter {

    // Plugin versions baked into generated builds — bump as upstreams release.
    private static final String FOOJAY = "0.9.0"; // org.gradle.toolchains.foojay-resolver-convention
    private static final String SHADOW = "8.3.6"; // com.gradleup.shadow
    private static final String NATIVE = "0.10.3"; // org.graalvm.buildtools.native

    /**
     * {@code settings} = settings.gradle.kts; {@code buildFiles} maps a project-relative dir ("" =
     * root) to its build.gradle.kts.
     */
    public record Result(String settings, Map<String, String> buildFiles, ImportReport report) {}

    private GradleExporter() {}

    /** Single-project export (auto layout). */
    public static Result export(JkBuild project, Map<String, String> locked) {
        return export(project, Map.of(), Map.of(), locked);
    }

    /** Workspace export with auto layout for every project. */
    public static Result export(JkBuild root, Map<String, JkBuild> modulesByRelPath, Map<String, String> locked) {
        return export(root, modulesByRelPath, Map.of(), locked);
    }

    /**
     * Export a project or workspace. {@code modulesByRelPath} (empty for a single project) maps each
     * module's path relative to the root to its parsed build; {@code layoutByRelPath} carries the
     * concrete {@link JkBuild.Layout} for each project keyed the same way (root = {@code ""}), so
     * jk's flat {@code SIMPLE} layout emits a matching {@code sourceSets} block (callers resolve
     * {@code AUTO} against the directory tree). Missing entries default to {@code AUTO}.
     */
    public static Result export(
            JkBuild root,
            Map<String, JkBuild> modulesByRelPath,
            Map<String, JkBuild.Layout> layoutByRelPath,
            Map<String, String> locked) {
        if (locked == null) locked = Map.of();
        if (layoutByRelPath == null) layoutByRelPath = Map.of();
        ImportReport.Builder report = ImportReport.builder();
        Map<String, String> buildFiles = new LinkedHashMap<>();

        String settings = renderSettings(root, modulesByRelPath.keySet());
        buildFiles.put("", renderBuild(root, layoutOf(layoutByRelPath, ""), locked, report));
        for (Map.Entry<String, JkBuild> e : modulesByRelPath.entrySet()) {
            buildFiles.put(
                    e.getKey(), renderBuild(e.getValue(), layoutOf(layoutByRelPath, e.getKey()), locked, report));
        }
        return new Result(settings, buildFiles, report.build());
    }

    private static JkBuild.Layout layoutOf(Map<String, JkBuild.Layout> map, String key) {
        JkBuild.Layout l = map.get(key);
        return l != null ? l : JkBuild.Layout.AUTO;
    }

    private static String renderSettings(JkBuild root, java.util.Set<String> moduleRelPaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n");
        sb.append("    // Auto-provisions JDK toolchains via the foojay Disco API (jk's `project.jdk`).\n");
        sb.append("    id(\"org.gradle.toolchains.foojay-resolver-convention\") version \"")
                .append(FOOJAY)
                .append("\"\n");
        sb.append("}\n\n");
        sb.append("rootProject.name = \"").append(kEsc(root.project().name())).append("\"\n");

        if (!moduleRelPaths.isEmpty()) {
            sb.append('\n');
            for (String rel : moduleRelPaths) {
                sb.append("include(\":").append(kEsc(rel.replace('/', ':'))).append("\")\n");
            }
        }
        return sb.toString();
    }

    private static String renderBuild(
            JkBuild jk, JkBuild.Layout layout, Map<String, String> locked, ImportReport.Builder report) {
        JkBuild.Project p = jk.project();
        boolean kotlin = p.kotlin() != null;
        boolean app = p.main() != null && !p.main().isBlank();
        boolean shadow = p.shadow();
        boolean nativeImg = p.nativeMode() == JkBuild.NativeMode.ALWAYS;

        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n");
        sb.append("    java\n");
        if (kotlin) {
            sb.append("    kotlin(\"jvm\") version \"")
                    .append(kEsc(extractVersion(p.kotlin(), "kotlin", report)))
                    .append("\"\n");
        }
        if (app) sb.append("    application\n");
        if (shadow)
            sb.append("    id(\"com.gradleup.shadow\") version \"")
                    .append(SHADOW)
                    .append("\"\n");
        if (nativeImg)
            sb.append("    id(\"org.graalvm.buildtools.native\") version \"")
                    .append(NATIVE)
                    .append("\"\n");
        sb.append("}\n\n");

        sb.append("group = \"").append(kEsc(p.group())).append("\"\n");
        sb.append("version = \"").append(kEsc(p.version())).append("\"\n\n");

        appendRepositories(sb, jk.repositories());
        appendDependencies(sb, jk, locked, report);
        appendToolchain(sb, p);
        appendSourceSets(sb, layout, kotlin);
        if (app) {
            sb.append("\napplication {\n    mainClass = \"")
                    .append(kEsc(p.main()))
                    .append("\"\n}\n");
        }
        appendManifest(sb, jk.manifest());
        warnDropped(jk, report);
        return sb.toString();
    }

    private static void appendRepositories(StringBuilder sb, List<RepositorySpec> repos) {
        sb.append("repositories {\n    mavenCentral()\n");
        for (RepositorySpec r : repos) {
            String u = r.url().toString();
            if (u.contains("repo.maven.apache.org") || u.contains("repo1.maven.org")) continue; // == mavenCentral
            sb.append("    maven { url = uri(\"").append(kEsc(u)).append("\") }\n");
        }
        sb.append("}\n\n");
    }

    private static void appendDependencies(
            StringBuilder sb, JkBuild jk, Map<String, String> locked, ImportReport.Builder report) {
        // jk scope → Gradle configuration (inverse of GradleImporter.mapConfiguration).
        record Pair(Scope scope, String config) {}
        Pair[] order = {
            new Pair(Scope.EXPORT, "api"),
            new Pair(Scope.MAIN, "implementation"),
            new Pair(Scope.PROVIDED, "compileOnly"),
            new Pair(Scope.RUNTIME, "runtimeOnly"),
            new Pair(Scope.PROCESSOR, "annotationProcessor"),
            new Pair(Scope.TEST, "testImplementation"),
        };
        sb.append("dependencies {\n");
        for (Dependency d : jk.dependencies().of(Scope.PLATFORM)) {
            if (warnIfUnmappable(d, report)) continue;
            sb.append("    implementation(platform(\"")
                    .append(kEsc(gav(d, locked, report)))
                    .append("\"))\n");
        }
        for (Pair pr : order) {
            for (Dependency d : jk.dependencies().of(pr.scope())) {
                if (warnIfUnmappable(d, report)) continue;
                sb.append("    ")
                        .append(pr.config())
                        .append("(\"")
                        .append(kEsc(gav(d, locked, report)))
                        .append("\")\n");
            }
        }
        sb.append("}\n");
    }

    /**
     * jk's {@link JkBuild.Layout#SIMPLE} layout is flat ({@code ./src}, {@code ./test}, {@code
     * ./resources}) where Gradle defaults to {@code src/main/java}. Remap the source sets so the
     * exported build compiles the same files. {@code TRADITIONAL} and {@code AUTO} already match
     * Gradle's convention — no block needed.
     */
    private static void appendSourceSets(StringBuilder sb, JkBuild.Layout layout, boolean kotlin) {
        if (layout != JkBuild.Layout.SIMPLE) return;
        sb.append("\nsourceSets {\n");
        sb.append("    main {\n");
        sb.append("        java.setSrcDirs(listOf(\"src\"))\n");
        if (kotlin) sb.append("        kotlin.setSrcDirs(listOf(\"src\"))\n");
        sb.append("        resources.setSrcDirs(listOf(\"resources\"))\n");
        sb.append("    }\n");
        sb.append("    test {\n");
        sb.append("        java.setSrcDirs(listOf(\"test\"))\n");
        if (kotlin) sb.append("        kotlin.setSrcDirs(listOf(\"test\"))\n");
        sb.append("        resources.setSrcDirs(listOf(\"test-resources\"))\n");
        sb.append("    }\n");
        sb.append("}\n");
    }

    private static void appendToolchain(StringBuilder sb, JkBuild.Project p) {
        int major = p.jdkMajor() > 0 ? p.jdkMajor() : p.javaRelease();
        if (major <= 0) return;
        sb.append("\njava {\n    toolchain {\n        languageVersion = JavaLanguageVersion.of(")
                .append(major)
                .append(")\n    }\n}\n");
    }

    private static void appendManifest(StringBuilder sb, Map<String, String> manifest) {
        if (manifest.isEmpty()) return;
        sb.append("\ntasks.jar {\n    manifest {\n        attributes(mapOf(\n");
        int i = 0;
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            sb.append("            \"")
                    .append(kEsc(e.getKey()))
                    .append("\" to \"")
                    .append(kEsc(e.getValue()))
                    .append('"');
            sb.append(++i < manifest.size() ? ",\n" : "\n");
        }
        sb.append("        ))\n    }\n}\n");
    }

    private static boolean warnIfUnmappable(Dependency d, ImportReport.Builder report) {
        if (d.isGit()) {
            report.warning("dependency `"
                    + d.module()
                    + "` is git-sourced; Gradle has no built-in"
                    + " git-source — dropped. Consider `includeBuild` of a local checkout.");
            return true;
        }
        if (d.isFile()) {
            report.warning("dependency `"
                    + d.module()
                    + "` is content-addressed (sha256); no Gradle"
                    + " equivalent — dropped.");
            return true;
        }
        return false;
    }

    private static String gav(Dependency d, Map<String, String> locked, ImportReport.Builder report) {
        String v = locked.get(d.module());
        if (v == null || v.isBlank()) v = extractVersion(d.version(), d.module(), report);
        return d.module() + ":" + v;
    }

    private static void warnDropped(JkBuild jk, ImportReport.Builder report) {
        if (!jk.features().isEmpty()) {
            report.warning("`features` block dropped — Gradle has no direct equivalent"
                    + " (consider feature variants / capabilities).");
        }
        if (jk.profiles() != null && !jk.profiles().byName().isEmpty()) {
            report.warning("`profiles` block dropped — Gradle has no first-class profile concept.");
        }
    }

    private static String extractVersion(VersionSelector v, String module, ImportReport.Builder report) {
        return switch (v) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> {
                report.warning("`"
                        + module
                        + "` declared `^"
                        + c.version()
                        + "`; pinned to `"
                        + c.version()
                        + "` (no jk.lock to resolve against).");
                yield c.version();
            }
            case VersionSelector.Tilde t -> {
                report.warning("`" + module + "` declared `~" + t.version() + "`; pinned to `" + t.version() + "`.");
                yield t.version();
            }
            case VersionSelector.Range r -> {
                report.warning("`"
                        + module
                        + "` uses range `"
                        + r.raw()
                        + "`; emitted verbatim — Gradle range syntax differs.");
                yield r.raw();
            }
            case VersionSelector.Latest l -> {
                report.warning("`" + module + "` uses `latest`; emitted as Gradle `latest.release`.");
                yield "latest.release";
            }
        };
    }

    /** Escape a Kotlin string literal ({@code \} and {@code "}). */
    private static String kEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
