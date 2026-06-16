// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the generated project tree from {@link NewInputs}:
 * <ul>
 *   <li>{@code jk.toml} via {@link NewJkBuildRenderer}</li>
 *   <li>empty {@code jk.lock}</li>
 *   <li>optional sample source tree (Java or Kotlin)</li>
 * </ul>
 *
 * The curated dependency map is the single source of truth for which "short
 * id" maps to which Maven coordinate + version + scope; both the renderer
 * and the wizard's MultiSelect step pull from it.
 */
public final class NewScaffolder {

    /**
     * Per-dep record. {@code version} is the major-version selector that ends
     * up after the {@code @} in the rendered TOML coord
     * (e.g., {@code org.projectlombok:lombok@1}). Floating @-form lets the
     * project pick up patch updates without an explicit bump.
     */
    public record CuratedEntry(String coord, String version, String scope) {}

    /**
     * Curated dependency catalog. The {@code version} is intentionally a bare
     * major (e.g. {@code "1"}); the renderer emits the {@code @}-form so the
     * resolver treats it as a floating caret selector ({@code ^1} → 1.x.x).
     */
    public static final Map<String, List<CuratedEntry>> CURATED_DEPS = Map.of(
            "lombok", List.of(
                    new CuratedEntry("org.projectlombok:lombok", "1", "processor"),
                    new CuratedEntry("org.projectlombok:lombok", "1", "provided")),
            "jspecify", List.of(
                    new CuratedEntry("org.jspecify:jspecify", "1", "main")),
            "commons-lang", List.of(
                    new CuratedEntry("org.apache.commons:commons-lang3", "3", "main")),
            "commons-io", List.of(
                    new CuratedEntry("commons-io:commons-io", "2", "main")),
            "guava", List.of(
                    new CuratedEntry("com.google.guava:guava", "33", "main")),
            "kotest", List.of(
                    new CuratedEntry("io.kotest:kotest-runner-junit6", "6", "test")));

    private NewScaffolder() {}

    public static void write(NewInputs inputs) throws IOException {
        write(inputs, true);
    }

    /**
     * Scaffold the project tree. When {@code writeLock} is false the project
     * is a workspace member: the per-member {@code jk.lock} and the
     * {@code .gitignore} are both skipped, since the workspace root owns
     * both (Cargo/uv: members never carry their own lock or gitignore).
     */
    public static void write(NewInputs inputs, boolean writeLock) throws IOException {
        var dir = inputs.directory();
        Files.createDirectories(dir);

        var buildFile = dir.resolve("jk.toml");
        Files.writeString(buildFile, NewJkBuildRenderer.render(inputs), StandardCharsets.UTF_8);

        if (writeLock) {
            var lockFile = dir.resolve("jk.lock");
            // Stamp the resolved JDK identifier (from the wizard pick) into the
            // lockfile so subsequent `jk` invocations see a pinned install. Falls
            // back to an unpinned empty lockfile when the flag path didn't
            // resolve to a specific install.
            var lock = inputs.jdkIdentifier()
                    .map(id -> Lockfile.empty(Jk.VERSION, id))
                    .orElseGet(() -> Lockfile.empty(Jk.VERSION));
            LockfileWriter.write(lock, lockFile);
        }

        if (writeLock) {
            writeGitignore(dir);   // members inherit the workspace root's .gitignore
        }

        if (inputs.sample()) {
            writeSample(inputs);
        }
    }

    /**
     * Seed a {@code .gitignore} covering jk's outputs. Don't clobber an
     * existing file — the user (or their template) may have customised
     * it. We only create one on first scaffold.
     */
    private static void writeGitignore(Path dir) throws IOException {
        Path gitignore = dir.resolve(".gitignore");
        if (Files.exists(gitignore)) return;
        Files.writeString(gitignore, """
                # jk build outputs
                target/
                .jk/
                """, StandardCharsets.UTF_8);
    }

    /** Class name used for the always-generated Main file. */
    private static final String MAIN_CLASS = "Main";

    /** First JDK feature release that supports instance main + implicit IO import. */
    private static final int JAVA_INSTANCE_MAIN_MIN = 25;

    private static void writeSample(NewInputs inputs) throws IOException {
        if (inputs.isRunnable()) {
            writeMain(inputs);
        } else {
            writePackageMarker(inputs);
        }
    }

    private static void writeMain(NewInputs inputs) throws IOException {
        var pkg = inputs.group();
        switch (inputs.lang()) {
            case JAVA -> {
                if (inputs.isSimpleLayout()) {
                    // Simple layout: package lives under ./src/{pkg}/ so the
                    // formatter and IDEA generator see a proper package structure.
                    var dir = inputs.directory().resolve("src/" + pkg.replace('.', '/'));
                    Files.createDirectories(dir);
                    // Gate on the compile target, not the toolchain.
                    var body = inputs.javaRelease() >= JAVA_INSTANCE_MAIN_MIN
                            ? renderJavaInstanceMain(pkg) : renderJavaTraditionalMain(pkg);
                    Files.writeString(dir.resolve(MAIN_CLASS + ".java"), body, StandardCharsets.UTF_8);
                } else {
                    var dir = inputs.directory().resolve("src/main/java/" + pkg.replace('.', '/'));
                    Files.createDirectories(dir);
                    var body = inputs.javaRelease() >= JAVA_INSTANCE_MAIN_MIN
                            ? renderJavaInstanceMain(pkg) : renderJavaTraditionalMain(pkg);
                    Files.writeString(dir.resolve(MAIN_CLASS + ".java"), body, StandardCharsets.UTF_8);
                }
            }
            case KOTLIN -> {
                Path file;
                String body;
                if (inputs.isSimpleLayout()) {
                    // Compact layout: no package, file lives at ./src/Main.kt.
                    var dir = inputs.directory().resolve("src");
                    Files.createDirectories(dir);
                    file = dir.resolve(MAIN_CLASS + ".kt");
                    body = renderKotlinMain("");
                } else {
                    var dir = inputs.directory().resolve("src/main/kotlin/" + pkg.replace('.', '/'));
                    Files.createDirectories(dir);
                    file = dir.resolve(MAIN_CLASS + ".kt");
                    body = renderKotlinMain(pkg);
                }
                Files.writeString(file, body, StandardCharsets.UTF_8);
            }
        }
    }

    private static void writePackageMarker(NewInputs inputs) throws IOException {
        var pkg = inputs.group();
        if (inputs.isSimpleLayout()) {
            // Compact layout: place a single source file at ./src/ with no nesting.
            var dir = inputs.directory().resolve("src");
            Files.createDirectories(dir);
            switch (inputs.lang()) {
                case JAVA -> Files.writeString(
                        dir.resolve("package-info.java"),
                        renderJavaPackageInfo(pkg), StandardCharsets.UTF_8);
                case KOTLIN -> Files.writeString(
                        dir.resolve("PackageInfo.kt"),
                        renderKotlinPackageMarker(pkg), StandardCharsets.UTF_8);
            }
        } else {
            var langDir = inputs.lang().sourceDir();
            var pkgDir = inputs.directory().resolve("src/main/" + langDir + "/" + pkg.replace('.', '/'));
            Files.createDirectories(pkgDir);
            switch (inputs.lang()) {
                case JAVA -> Files.writeString(
                        pkgDir.resolve("package-info.java"),
                        renderJavaPackageInfo(pkg), StandardCharsets.UTF_8);
                case KOTLIN -> Files.writeString(
                        pkgDir.resolve("PackageInfo.kt"),
                        renderKotlinPackageMarker(pkg), StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * JEP 512 entry point (JDK 25+): a no-arg instance {@code main} with the
     * implicit {@code IO}/{@code java.lang.System} imports, wrapped in an explicit
     * {@code class Main}. When {@code pkg} is empty the class is emitted without a
     * package declaration (unnamed package / compact source file).
     */
    private static String renderJavaInstanceMain(String pkg) {
        if (pkg.isEmpty()) {
            return """
                    void main() {
                        IO.println("Hello, world!");
                    }
                    """;
        }
        return "package " + pkg + ";\n\n"
                + "class " + MAIN_CLASS + " {\n"
                + "    void main() {\n"
                + "        IO.println(\"Hello, world!\");\n"
                + "    }\n"
                + "}\n";
    }

    /** Classic entry point for JDK &lt; 25: an explicit {@code class Main} with a static main. */
    private static String renderJavaTraditionalMain(String pkg) {
        String prefix = pkg.isEmpty() ? "" : "package " + pkg + ";\n\n";
        return prefix
                + "class " + MAIN_CLASS + " {\n"
                + "    public static void main(String... args) {\n"
                + "        System.out.println(\"Hello, world!\");\n"
                + "    }\n"
                + "}\n";
    }

    private static String renderKotlinMain(String pkg) {
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
