// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the generated project tree from {@link InitInputs}:
 * <ul>
 *   <li>{@code build.jk} via {@link InitBuildJkRenderer}</li>
 *   <li>empty {@code jk.lock}</li>
 *   <li>optional sample source tree (Java or Kotlin)</li>
 * </ul>
 *
 * The curated dependency map is the single source of truth for which "short
 * id" maps to which Maven coordinate + version + scope; both the renderer
 * and the wizard's MultiSelect step pull from it.
 */
public final class InitScaffolder {

    /** Per-dep records: a single id may expand into multiple scoped entries (Lombok wants both processor + provided). */
    public record CuratedEntry(String coord, String version, String scope) {}

    /**
     * Curated dependency catalog. Versions live here so the owner can bump
     * them in one place; the wizard / flag CSV reference these short ids.
     */
    public static final Map<String, List<CuratedEntry>> CURATED_DEPS = Map.of(
            "lombok", List.of(
                    new CuratedEntry("org.projectlombok:lombok", "1.18.34", "processor"),
                    new CuratedEntry("org.projectlombok:lombok", "1.18.34", "provided")),
            "commons-lang", List.of(
                    new CuratedEntry("org.apache.commons:commons-lang3", "3.17.0", "main")),
            "commons-io", List.of(
                    new CuratedEntry("commons-io:commons-io", "2.16.1", "main")),
            "guava", List.of(
                    new CuratedEntry("com.google.guava:guava", "33.4.0-jre", "main")));

    private InitScaffolder() {}

    public static void write(InitInputs inputs) throws IOException {
        var dir = inputs.directory();
        Files.createDirectories(dir);

        var buildFile = dir.resolve("jk.toml");
        Files.writeString(buildFile, InitBuildJkRenderer.render(inputs), StandardCharsets.UTF_8);

        var lockFile = dir.resolve("jk.lock");
        LockfileWriter.write(Lockfile.empty(Jk.VERSION), lockFile);

        if (inputs.sample()) {
            writeSample(inputs);
        }
    }

    private static void writeSample(InitInputs inputs) throws IOException {
        var groupPath = inputs.group().replace('.', '/');
        var langDir = inputs.lang().sourceDir();
        var pkgDir = inputs.directory().resolve("src/main/" + langDir + "/" + groupPath);
        Files.createDirectories(pkgDir);

        if (inputs.isRunnable()) {
            var fqcn = inputs.main().orElseThrow();
            var split = splitFqcn(fqcn);
            var pkg = split.pkg();
            var cls = split.className();
            // The user's main may not match the group package; honor whatever they typed.
            var samplePkgDir = pkg.isEmpty()
                    ? inputs.directory().resolve("src/main/" + langDir)
                    : inputs.directory().resolve("src/main/" + langDir + "/" + pkg.replace('.', '/'));
            Files.createDirectories(samplePkgDir);

            switch (inputs.lang()) {
                case JAVA -> Files.writeString(
                        samplePkgDir.resolve(cls + ".java"),
                        renderJavaApp(pkg, cls),
                        StandardCharsets.UTF_8);
                case KOTLIN -> Files.writeString(
                        samplePkgDir.resolve(cls + ".kt"),
                        renderKotlinApp(pkg),
                        StandardCharsets.UTF_8);
            }
        } else {
            switch (inputs.lang()) {
                case JAVA -> Files.writeString(
                        pkgDir.resolve("package-info.java"),
                        renderJavaPackageInfo(inputs.group()),
                        StandardCharsets.UTF_8);
                case KOTLIN -> Files.writeString(
                        pkgDir.resolve("PackageInfo.kt"),
                        renderKotlinPackageMarker(inputs.group()),
                        StandardCharsets.UTF_8);
            }
        }
    }

    private record FqcnSplit(String pkg, String className) {}

    private static FqcnSplit splitFqcn(String fqcn) {
        var dot = fqcn.lastIndexOf('.');
        if (dot < 0) {
            return new FqcnSplit("", fqcn);
        }
        return new FqcnSplit(fqcn.substring(0, dot), fqcn.substring(dot + 1));
    }

    private static String renderJavaApp(String pkg, String className) {
        var sb = new StringBuilder();
        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }
        sb.append("public final class ").append(className).append(" {\n");
        sb.append("    public static void main(String[] args) {\n");
        sb.append("        System.out.println(\"Hello, world!\");\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String renderKotlinApp(String pkg) {
        var sb = new StringBuilder();
        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append("\n\n");
        }
        sb.append("fun main() {\n");
        sb.append("    println(\"Hello, world!\")\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String renderJavaPackageInfo(String pkg) {
        return "/** " + pkg + " package. */\npackage " + pkg + ";\n";
    }

    private static String renderKotlinPackageMarker(String pkg) {
        return "package " + pkg + "\n";
    }
}
