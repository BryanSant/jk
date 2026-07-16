// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the generated project tree from {@link NewInputs}:
 *
 * <ul>
 *   <li>{@code jk.toml} via {@link NewJkBuildRenderer}
 *   <li>the production + test source roots (see {@link #createSourceTree})
 *   <li>optional sample source tree (Java or Kotlin)
 * </ul>
 *
 * <p>No {@code jk.lock} — that's generated on the first build/run.
 *
 * <p>The curated dependency map is the single source of truth for which "short id" maps to which
 * Maven coordinate + version + scope; both the renderer and the wizard's MultiSelect step pull from
 * it.
 */
public final class NewScaffolder {

    /**
     * Per-dep record. {@code version} is the major-version selector that ends up after the {@code @}
     * in the rendered TOML coord (e.g., {@code org.projectlombok:lombok@1}). Floating @-form lets the
     * project pick up patch updates without an explicit bump.
     */
    public record CuratedEntry(String coord, String version, String scope) {}

    /**
     * Curated dependency catalog. The {@code version} is intentionally a bare major (e.g. {@code
     * "1"}); the renderer emits the {@code @}-form so the resolver treats it as a floating caret
     * selector ({@code ^1} → 1.x.x).
     */
    public static final Map<String, List<CuratedEntry>> CURATED_DEPS = Map.of(
            "lombok",
                    List.of(
                            new CuratedEntry("org.projectlombok:lombok", "1", "processor"),
                            new CuratedEntry("org.projectlombok:lombok", "1", "provided")),
            "jspecify", List.of(new CuratedEntry("org.jspecify:jspecify", "1", "main")),
            "commons-lang", List.of(new CuratedEntry("org.apache.commons:commons-lang3", "3", "main")),
            "commons-io", List.of(new CuratedEntry("commons-io:commons-io", "2", "main")),
            "guava", List.of(new CuratedEntry("com.google.guava:guava", "33", "main")),
            "kotest", List.of(new CuratedEntry("io.kotest:kotest-runner-junit6", "6", "test")));

    private NewScaffolder() {}

    public static void write(NewInputs inputs) throws IOException {
        write(inputs, true);
    }

    /**
     * Scaffold the project tree. {@code standalone} is false for a workspace module, whose {@code
     * .gitignore} is owned by the workspace root and so is skipped here (Cargo/uv: modules never
     * carry their own gitignore).
     *
     * <p>No {@code jk.lock} is written — it's generated on the first build or run, so a
     * freshly-scaffolded project carries only its manifest + sources.
     *
     * <p>A plugin scaffold ({@code --spring}) fetches its payloads BEFORE anything touches disk:
     * the engine renders the plugin's {@code [scaffold]} data (the jk.toml fragments + sample
     * templates — content the thin client does not ship), and a failure leaves no half-written
     * project behind.
     */
    public static void write(NewInputs inputs, boolean standalone) throws IOException {
        var dir = inputs.directory();
        cc.jumpkick.engine.protocol.GeneratedFiles plugin = inputs.spring() ? pluginScaffold(inputs) : null;

        Files.createDirectories(dir);
        if (plugin != null) {
            for (int i = 0; i < plugin.paths().size(); i++) {
                Path target = Path.of(plugin.paths().get(i));
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.writeString(target, plugin.contents().get(i), StandardCharsets.UTF_8);
            }
        } else {
            Files.writeString(dir.resolve("jk.toml"), NewJkBuildRenderer.render(inputs), StandardCharsets.UTF_8);
        }

        if (standalone) {
            writeGitignore(dir); // modules inherit the workspace root's .gitignore
        }

        createSourceTree(inputs);

        if (plugin == null && inputs.sample()) {
            writeSample(inputs);
        }
    }

    /**
     * The engine-rendered plugin scaffold: base jk.toml + the plugin's fragments, and the sample
     * files when requested. {@code plugin} is the scaffold flag ({@code spring}); generation is
     * engine-hosted so the client carries none of the framework content.
     */
    private static cc.jumpkick.engine.protocol.GeneratedFiles pluginScaffold(NewInputs inputs) throws IOException {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("plugin", "spring");
        params.put("lang", inputs.lang() == NewInputs.Language.KOTLIN ? "kotlin" : "java");
        params.put("package", inputs.group());
        params.put("simpleLayout", String.valueOf(inputs.isSimpleLayout()));
        params.put("sample", String.valueOf(inputs.sample()));
        params.put("baseToml", NewJkBuildRenderer.render(inputs));
        var files = ExportSupport.generate(inputs.directory(), "scaffold", params, "jk new");
        if (files == null) throw new IOException("jk new: plugin scaffold failed");
        return files;
    }

    /**
     * Ensure the production and test source roots both exist from the start: {@code src/} + {@code
     * test/} for the simple layout, or {@code src/main/<lang>} + {@code src/test/<lang>} for the
     * traditional one.
     */
    private static void createSourceTree(NewInputs inputs) throws IOException {
        var dir = inputs.directory();
        if (inputs.isSimpleLayout()) {
            Files.createDirectories(dir.resolve("src"));
            Files.createDirectories(dir.resolve("test"));
        } else {
            String lang = inputs.lang() == NewInputs.Language.KOTLIN ? "kotlin" : "java";
            Files.createDirectories(dir.resolve("src").resolve("main").resolve(lang));
            Files.createDirectories(dir.resolve("src").resolve("test").resolve(lang));
        }
    }

    /**
     * Seed a {@code .gitignore} covering jk's outputs. Don't clobber an existing file — the user (or
     * their template) may have customised it. We only create one on first scaffold.
     */
    private static void writeGitignore(Path dir) throws IOException {
        Path gitignore = dir.resolve(".gitignore");
        if (Files.exists(gitignore)) return;
        Files.writeString(gitignore, """
                # jk build outputs
                target/
                .jk/

                # IntelliJ IDEA
                .idea/
                *.iml
                *.ipr
                *.iws
                out/

                # VS Code
                .vscode/
                *.code-workspace
                .history/

                # Eclipse compiler (used by VS Code Java plugins)
                .classpath
                .project
                .settings/
                .factorypath
                **/src/**/bin/
                **/test/**/bin/
                """, StandardCharsets.UTF_8);
    }

    /** Class name used for the always-generated Main file. */
    private static final String MAIN_CLASS = "Main";

    /** First JDK feature release that supports instance main + implicit IO import. */
    private static final int JAVA_INSTANCE_MAIN_MIN = 25;

    /**
     * Sample sources mirroring jk's reference layout: a {@code Calc} class with a JUnit {@code
     * CalcTest}, plus a {@code Main} entry point for runnable projects. The package follows the
     * project group; the test relies on the JUnit jk defaults in when no test framework is declared.
     */
    private static void writeSample(NewInputs inputs) throws IOException {
        switch (inputs.lang()) {
            case JAVA -> writeJavaSample(inputs);
            case KOTLIN -> writeKotlinSample(inputs);
        }
    }

    private static void writeJavaSample(NewInputs inputs) throws IOException {
        String pkg = inputs.group();
        String pkgPath = "/" + pkg.replace('.', '/');
        Path srcDir = inputs.directory().resolve(mainSourceRoot(inputs) + pkgPath);
        Path testDir = inputs.directory().resolve(testSourceRoot(inputs) + pkgPath);
        Files.createDirectories(srcDir);
        Files.createDirectories(testDir);

        Files.writeString(srcDir.resolve("Calc.java"), renderJavaCalc(pkg), StandardCharsets.UTF_8);
        Files.writeString(testDir.resolve("CalcTest.java"), renderJavaCalcTest(pkg), StandardCharsets.UTF_8);
        if (inputs.isRunnable()) {
            // Gate the instance-main syntax on the compile target, not the toolchain.
            boolean instanceMain = inputs.javaRelease() >= JAVA_INSTANCE_MAIN_MIN;
            Files.writeString(
                    srcDir.resolve(MAIN_CLASS + ".java"), renderJavaMain(pkg, instanceMain), StandardCharsets.UTF_8);
        }
    }

    private static void writeKotlinSample(NewInputs inputs) throws IOException {
        // Kotlin keeps its compact convention: the simple layout is package-less
        // (files at ./src and ./test); the traditional layout nests by package.
        boolean simple = inputs.isSimpleLayout();
        String pkg = simple ? "" : inputs.group();
        String pkgPath = pkg.isEmpty() ? "" : "/" + pkg.replace('.', '/');
        Path srcDir = inputs.directory().resolve((simple ? "src" : "src/main/kotlin") + pkgPath);
        Path testDir = inputs.directory().resolve((simple ? "test" : "src/test/kotlin") + pkgPath);
        Files.createDirectories(srcDir);
        Files.createDirectories(testDir);

        Files.writeString(srcDir.resolve("Calc.kt"), renderKotlinCalc(pkg), StandardCharsets.UTF_8);
        Files.writeString(testDir.resolve("CalcTest.kt"), renderKotlinCalcTest(pkg), StandardCharsets.UTF_8);
        if (inputs.isRunnable()) {
            Files.writeString(srcDir.resolve(MAIN_CLASS + ".kt"), renderKotlinMain(pkg), StandardCharsets.UTF_8);
        }
    }

    /** Production source root: {@code src} (simple) or {@code src/main/<lang>} (traditional). */
    private static String mainSourceRoot(NewInputs inputs) {
        return inputs.isSimpleLayout() ? "src" : "src/main/" + inputs.lang().sourceDir();
    }

    /** Test source root: {@code test} (simple) or {@code src/test/<lang>} (traditional). */
    private static String testSourceRoot(NewInputs inputs) {
        return inputs.isSimpleLayout() ? "test" : "src/test/" + inputs.lang().sourceDir();
    }

    /**
     * Entry point referencing the sample {@code Calc}. JDK 25+ gets the JEP 512 instance {@code main}
     * with the implicit {@code IO} import; older targets get a classic static {@code main}.
     */
    private static String renderJavaMain(String pkg, boolean instanceMain) {
        if (instanceMain) {
            return """
                    package %s;

                    class Main {
                        void main() {
                            int value = 5;
                            Calc calc = new Calc();
                            IO.println("Hello, world! 5 * 2 = " + calc.doubleValue(value));
                        }
                    }
                    """.formatted(pkg);
        }
        return """
                package %s;

                class Main {
                    public static void main(String... args) {
                        int value = 5;
                        Calc calc = new Calc();
                        System.out.println("Hello, world! 5 * 2 = " + calc.doubleValue(value));
                    }
                }
                """.formatted(pkg);
    }

    private static String renderJavaCalc(String pkg) {
        return """
                package %s;

                public class Calc {
                    public int doubleValue(int value) {
                        return value * 2;
                    }
                }
                """.formatted(pkg);
    }

    private static String renderJavaCalcTest(String pkg) {
        return """
                package %s;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                public class CalcTest {
                    @Test
                    void doubleValueReturnsTwiceTheInput() {
                        Calc calc = new Calc();
                        assertEquals(10, calc.doubleValue(5));
                    }
                }
                """.formatted(pkg);
    }

    private static String renderKotlinMain(String pkg) {
        return pkgHeaderKt(pkg) + """
                fun main() {
                    val value = 5
                    val calc = Calc()
                    println("Hello, world! 5 * 2 = ${calc.doubleValue(value)}")
                }
                """;
    }

    private static String renderKotlinCalc(String pkg) {
        return pkgHeaderKt(pkg) + """
                class Calc {
                    fun doubleValue(value: Int): Int = value * 2
                }
                """;
    }

    private static String renderKotlinCalcTest(String pkg) {
        return pkgHeaderKt(pkg) + """
                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class CalcTest {
                    @Test
                    fun doubleValueReturnsTwiceTheInput() {
                        assertEquals(10, Calc().doubleValue(5))
                    }
                }
                """;
    }

    /** {@code "package <pkg>\n\n"}, or empty for the package-less (compact Kotlin) case. */
    private static String pkgHeaderKt(String pkg) {
        return pkg.isEmpty() ? "" : "package " + pkg + "\n\n";
    }
}
