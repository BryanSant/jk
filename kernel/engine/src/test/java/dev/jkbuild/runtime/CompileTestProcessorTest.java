// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.PhaseContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression guard for the compile-test annotation-processor wiring (the bug where
 * {@code jk}'s test compilation never ran declared processors). {@link
 * TestSupport#compileWithCache} must put the processor path on the request — modern
 * javac (JDK 23+) only runs processors named by {@code -processorpath}, not ones
 * merely on the classpath. The fixture uses a source-generating processor: a test
 * source annotated with {@code @gen.Gen} references the generated {@code <Type>Gen}
 * type, so if the processor doesn't run the symbol is missing and the compile fails.
 */
class CompileTestProcessorTest {

    private static final String TEST_SRC =
            "package app; @gen.Gen public class WidgetTest { final Object g = new WidgetTestGen(); }";

    @Test
    void processorRunsOverTestSources_whenProcessorPathProvided(@TempDir Path dir) throws Exception {
        Path procDir = sourceGenProcessor(dir);
        Path testSrc = dir.resolve("test");
        write(testSrc, "app/WidgetTest.java", TEST_SRC);
        Path out = dir.resolve("out");

        boolean ok = TestSupport.compileWithCache(
                new NoopContext(),
                "compile-test",
                testSrc,
                out,
                List.of(procDir),
                List.of(procDir), // processor jar on cp AND processorpath
                21,
                List.of(),
                Path.of(System.getProperty("java.home")),
                null,
                new Cas(dir.resolve("cas")),
                dir.resolve("cache"));

        assertThat(ok).isTrue();
        assertThat(out.resolve("app/WidgetTestGen.class")).isRegularFile(); // processor ran
        assertThat(out.resolve("app/WidgetTest.class")).isRegularFile();
    }

    @Test
    void processorDoesNotRun_whenProcessorPathOmitted(@TempDir Path dir) throws Exception {
        // The exact pre-fix behavior: the processor jar is on the classpath but NOT the
        // processor path. JDK 23+ won't auto-run classpath processors, so WidgetTestGen
        // is never generated and the test source fails to compile. (Older javac would
        // auto-run it, so the assertion is meaningful only on 23+.)
        assumeTrue(Runtime.version().feature() >= 23, "javac classpath AP auto-run removed in 23");

        Path procDir = sourceGenProcessor(dir);
        Path testSrc = dir.resolve("test");
        write(testSrc, "app/WidgetTest.java", TEST_SRC);
        Path out = dir.resolve("out");

        boolean ok = TestSupport.compileWithCache(
                new NoopContext(),
                "compile-test",
                testSrc,
                out,
                List.of(procDir),
                List.of(), // NO processor path — the regression
                21,
                List.of(),
                Path.of(System.getProperty("java.home")),
                null,
                new Cas(dir.resolve("cas")),
                dir.resolve("cache"));

        assertThat(ok).isFalse();
        assertThat(out.resolve("app/WidgetTestGen.class")).doesNotExist();
    }

    // ---- fixtures ---------------------------------------------------------

    /** Source-generating processor: emits {@code <Type>Gen} for each {@code @gen.Gen} type. */
    private static Path sourceGenProcessor(Path dir) throws IOException {
        Path procDir = dir.resolve("proc");
        compile(
                procDir,
                Map.of(
                        "gen.Gen", """
                        package gen;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE)
                        public @interface Gen {}
                        """,
                        "gen.GenProc", """
                        package gen;
                        import javax.annotation.processing.*;
                        import javax.lang.model.SourceVersion;
                        import javax.lang.model.element.*;
                        import javax.tools.JavaFileObject;
                        import java.io.*;
                        import java.util.Set;
                        @SupportedAnnotationTypes("gen.Gen")
                        public class GenProc extends AbstractProcessor {
                            public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                            public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) {
                                for (Element e : r.getElementsAnnotatedWith(Gen.class)) {
                                    if (!(e instanceof TypeElement t)) continue;
                                    String pkg = processingEnv.getElementUtils().getPackageOf(t).getQualifiedName().toString();
                                    String name = (pkg.isEmpty()?"":pkg+".") + t.getSimpleName() + "Gen";
                                    try {
                                        JavaFileObject f = processingEnv.getFiler().createSourceFile(name, t);
                                        try (Writer w = f.openWriter()) {
                                            w.write((pkg.isEmpty()?"":"package "+pkg+";\\n") + "public class " + t.getSimpleName() + "Gen {}\\n");
                                        }
                                    } catch (IOException ex) { throw new UncheckedIOException(ex); }
                                }
                                return true;
                            }
                        }
                        """));
        Files.writeString(
                Files.createDirectories(procDir.resolve("META-INF/services"))
                        .resolve("javax.annotation.processing.Processor"),
                "gen.GenProc\n");
        return procDir;
    }

    private static void write(Path root, String rel, String body) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
    }

    private static void compile(Path outDir, Map<String, String> sources) throws IOException {
        Path srcDir = outDir.resolve("_src");
        Files.createDirectories(outDir);
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path f = srcDir.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f.toString());
        }
        List<String> args = new ArrayList<>(List.of("-d", outDir.toString()));
        args.addAll(files);
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0]));
        if (rc != 0) throw new IllegalStateException("fixture javac failed, rc=" + rc);
    }

    /** Minimal PhaseContext — compileWithCache only labels / warns / errors / reweights. */
    private static final class NoopContext implements PhaseContext {
        @Override
        public void progress(int delta) {}

        @Override
        public void updateScope(int additionalScope) {}

        @Override
        public void label(String description) {}

        @Override
        public void output(String line) {}

        @Override
        public void warn(String code, String message) {}

        @Override
        public void error(String code, String message) {}

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public <T> void put(GoalKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(GoalKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(GoalKey<T> key) {
            throw new IllegalStateException("no " + key);
        }
    }
}
