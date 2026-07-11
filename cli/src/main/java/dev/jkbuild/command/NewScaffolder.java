// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

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

    /**
     * Spring Boot version {@code jk new --spring} writes into {@code [spring-boot]} — the newest
     * GA line at release time. Users bump it in jk.toml; walk it forward with jk releases.
     */
    public static final String DEFAULT_BOOT_VERSION = "4.1.0";

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
     */
    public static void write(NewInputs inputs, boolean standalone) throws IOException {
        var dir = inputs.directory();
        Files.createDirectories(dir);

        var buildFile = dir.resolve("jk.toml");
        Files.writeString(buildFile, NewJkBuildRenderer.render(inputs), StandardCharsets.UTF_8);

        if (standalone) {
            writeGitignore(dir); // modules inherit the workspace root's .gitignore
        }

        createSourceTree(inputs);

        if (inputs.sample()) {
            writeSample(inputs);
        }
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
        if (inputs.spring()) {
            writeSpringSample(inputs);
            return;
        }
        switch (inputs.lang()) {
            case JAVA -> writeJavaSample(inputs);
            case KOTLIN -> writeKotlinSample(inputs);
        }
    }

    /**
     * Spring Boot starter app: an {@code Application} class doubling as a REST controller, an
     * {@code application.properties} seed, and a plain-JUnit test of the handler (no context
     * bootstrap — the sample tests stay dependency-free beyond jk's JUnit defaults).
     */
    private static void writeSpringSample(NewInputs inputs) throws IOException {
        String pkg = inputs.group();
        String pkgPath = "/" + pkg.replace('.', '/');
        boolean kotlin = inputs.lang() == NewInputs.Language.KOTLIN;
        Path srcDir = inputs.directory().resolve(mainSourceRoot(inputs) + pkgPath);
        Path testDir = inputs.directory().resolve(testSourceRoot(inputs) + pkgPath);
        Path resourcesDir = inputs.directory()
                .resolve(inputs.isSimpleLayout() ? "src" : "src/main/resources");
        Files.createDirectories(srcDir);
        Files.createDirectories(testDir);
        Files.createDirectories(resourcesDir);

        if (kotlin) {
            Files.writeString(srcDir.resolve("Application.kt"), renderSpringApplicationKt(pkg), StandardCharsets.UTF_8);
            Files.writeString(
                    testDir.resolve("ApplicationTest.kt"), renderSpringApplicationTestKt(pkg), StandardCharsets.UTF_8);
        } else {
            Files.writeString(
                    srcDir.resolve("Application.java"), renderSpringApplication(pkg), StandardCharsets.UTF_8);
            Files.writeString(
                    testDir.resolve("ApplicationTest.java"), renderSpringApplicationTest(pkg), StandardCharsets.UTF_8);
        }
        Path props = resourcesDir.resolve("application.properties");
        if (!Files.exists(props)) {
            Files.writeString(props, """
                    # server.port=8080
                    """, StandardCharsets.UTF_8);
        }
    }

    private static String renderSpringApplicationKt(String pkg) {
        return """
                package %s

                import org.springframework.boot.autoconfigure.SpringBootApplication
                import org.springframework.boot.runApplication
                import org.springframework.web.bind.annotation.GetMapping
                import org.springframework.web.bind.annotation.RestController

                @SpringBootApplication
                @RestController
                class Application {

                    @GetMapping("/")
                    fun hello(): String = "Hello from jk + Spring Boot!"
                }

                fun main(args: Array<String>) {
                    runApplication<Application>(*args)
                }
                """.formatted(pkg);
    }

    private static String renderSpringApplicationTestKt(String pkg) {
        return """
                package %s

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class ApplicationTest {
                    @Test
                    fun helloGreets() {
                        assertEquals("Hello from jk + Spring Boot!", Application().hello())
                    }
                }
                """.formatted(pkg);
    }

    private static String renderSpringApplication(String pkg) {
        return """
                package %s;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @SpringBootApplication
                @RestController
                public class Application {

                    public static void main(String[] args) {
                        SpringApplication.run(Application.class, args);
                    }

                    @GetMapping("/")
                    public String hello() {
                        return "Hello from jk + Spring Boot!";
                    }
                }
                """.formatted(pkg);
    }

    private static String renderSpringApplicationTest(String pkg) {
        return """
                package %s;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                public class ApplicationTest {
                    @Test
                    void helloGreets() {
                        assertEquals("Hello from jk + Spring Boot!", new Application().hello());
                    }
                }
                """.formatted(pkg);
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
