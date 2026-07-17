// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Annotation-processor-aware incremental compilation (slice 3b) end-to-end through {@link
 * JavaIncrementalCompile#run} against the real {@code jk-java-compiler} worker.
 *
 * <p>Exercises the new surface: the orphan-signal detection that flips a project into worker mode,
 * the provenance-arity gate that classifies <em>isolating</em> vs <em>aggregating</em> processors,
 * the incremental tier actually running for an isolating processor, and the safe full-rebuild
 * fallback for an aggregating one. The underlying dirty-set/ABI machinery is shared with {@link
 * JavaIncrementalCompileTest}.
 */
class JavaApIncrementalCompileTest {

    /**
     * Isolating processor (1 generated file per annotated type → arity 1): the project is detected on
     * build 1, establishes provenance state on the first worker build, then takes the incremental
     * tier on a subsequent body edit — without going stale.
     */
    @Test
    void isolating_processor_detected_then_incrementally_compiles_via_worker(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        Path procDir = isolatingProcessor(dir);
        Project p = new Project(dir, worker, procDir);

        // v1 — build 1 runs plain subprocess javac (no worker yet); the AP-generated
        // WidgetGen.class is an "orphan" (no provenance) → project flagged source-gen.
        p.write("app/Widget.java", widget("one"));
        JavaIncrementalCompile.Result b1 = p.build();
        assertThat(b1.success()).isTrue();
        assertThat(p.apFlags()).contains("sourceGenAps=true");

        // v2 — now in worker mode; no usable state yet → full compile via the worker,
        // which captures provenance, attributes WidgetGen to Widget, and classifies the
        // processor isolating.
        p.write("app/Widget.java", widget("two"));
        JavaIncrementalCompile.Result b2 = p.build();
        assertThat(b2.success()).isTrue();
        assertThat(p.classFile("app/WidgetGen.class")).isRegularFile();
        assertThat(p.classFile("app/Widget.class")).isRegularFile();
        assertThat(Files.isRegularFile(p.stateDir.resolve("java-incremental.txt")))
                .isTrue();
        assertThat(p.apFlags()).contains("isolating=true");

        // A non-class sentinel distinguishes the tiers: the incremental tier restores the
        // output dir (recursive wipe) before recompiling, so the sentinel vanishes; a full
        // rebuild only deletes *.class and would leave it.
        Path sentinel = p.out.resolve("INCREMENTAL_SENTINEL");
        Files.writeString(sentinel, "x");

        // v3 — body edit (ABI-stable): isolating + usable state → incremental tier.
        p.write("app/Widget.java", widget("three"));
        JavaIncrementalCompile.Result b3 = p.build();
        assertThat(b3.success()).isTrue();
        assertThat(Files.exists(sentinel))
                .as("incremental tier restores (wipes) the output dir")
                .isFalse();
        // Never stale: the generated class is still present and Widget reflects the v3 edit.
        assertThat(p.classFile("app/WidgetGen.class")).isRegularFile();
        assertThat(p.invokeGreet()).isEqualTo("three");
    }

    /**
     * Aggregating processor (one generated file from many originating sources → arity &gt;1): the
     * arity gate classifies it non-isolating, so the project safely stays on full rebuilds instead of
     * risking a stale aggregate from a subset recompile.
     */
    @Test
    void aggregating_processor_is_classified_non_isolating_and_stays_full(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        Path procDir = aggregatingProcessor(dir);
        Project p = new Project(dir, worker, procDir);

        p.write("app/Alpha.java", "package app; @reg.Reg public class Alpha {}");
        p.write("app/Beta.java", "package app; @reg.Reg public class Beta {}");
        p.build(); // build 1: detect (subprocess javac)

        p.write("app/Alpha.java", "package app; @reg.Reg public class Alpha { int v; }");
        p.build(); // build 2: worker full → arity-2 provenance
        assertThat(p.classFile("app/Registry.class")).isRegularFile();
        assertThat(p.apFlags()).contains("isolating=false");

        // Stays full: a sentinel placed before an edit survives (full deletes only *.class).
        Path sentinel = p.out.resolve("FULL_SENTINEL");
        Files.writeString(sentinel, "x");
        p.write("app/Beta.java", "package app; @reg.Reg public class Beta { int v; }");
        p.build();
        assertThat(Files.exists(sentinel))
                .as("aggregating project stays on full rebuilds")
                .isTrue();
        assertThat(p.classFile("app/Registry.class")).isRegularFile(); // correct aggregate
    }

    // ---- harness ----------------------------------------------------------

    private static Path workerJar() {
        String prop = System.getProperty("jk.java.plugin.jar");
        assumeTrue(
                prop != null && Files.isRegularFile(Path.of(prop)),
                "jk.java.plugin.jar must point at the built worker jar");
        return Path.of(prop);
    }

    private static String widget(String tag) {
        return "package app; @gen.Gen public class Widget { public String greet() { return \"" + tag + "\"; } }";
    }

    private final class Project {
        final Path root;
        final Path srcRoot;
        final Path out;
        final Path genSrc;
        final Cas cas;
        final ActionCache actionCache;
        final Path stateDir;
        final Path workerJar;
        final Path procDir;

        Project(Path root, Path workerJar, Path procDir) throws IOException {
            this.root = root;
            this.srcRoot = root.resolve("src/main/java");
            this.out = root.resolve("out");
            this.genSrc = root.resolve("gen-src");
            this.cas = new Cas(root.resolve("cas"));
            this.actionCache = new ActionCache(cas, root.resolve("actions"));
            this.stateDir = root.resolve("state");
            this.workerJar = workerJar;
            this.procDir = procDir;
            Files.createDirectories(srcRoot);
        }

        void write(String rel, String body) throws IOException {
            Path f = srcRoot.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, body);
        }

        Path classFile(String rel) {
            return out.resolve(rel);
        }

        String apFlags() throws IOException {
            return Files.readString(stateDir.resolve("java-ap.txt"));
        }

        JavaIncrementalCompile.Result build() throws IOException {
            List<Path> sources = new ArrayList<>();
            try (var s = Files.walk(srcRoot)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.toString().endsWith(".java")) sources.add(p);
                }
            }
            CompileRequest req = CompileRequest.builder()
                    .sources(sources)
                    .classpath(List.of(procDir)) // so @Gen/@Reg resolve
                    .outputDir(out)
                    .release(21)
                    // The processor path makes javac run the AP (modern javac won't
                    // auto-run classpath processors) — the plain-javac build that surfaces
                    // the orphan signal flipping the project into worker mode.
                    .processorPath(List.of(procDir))
                    .javaHome(Path.of(System.getProperty("java.home")))
                    .build();
            var ap = new JavaIncrementalCompile.ApSetup(() -> workerJar, genSrc);
            JavaIncrementalCompile.Result r =
                    JavaIncrementalCompile.run("compile-main", req, "jk-test", true, cas, actionCache, stateDir, ap);
            assertThat(r.success()).as("compile succeeded: %s", r.diagnostics()).isTrue();
            return r;
        }

        String invokeGreet() throws Exception {
            try (URLClassLoader cl = new URLClassLoader(
                    new URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
                Class<?> c = Class.forName("app.Widget", true, cl);
                Object w = c.getDeclaredConstructor().newInstance();
                return (String) c.getMethod("greet").invoke(w);
            }
        }
    }

    // ---- processors compiled onto a processor path ------------------------

    /** Isolating: one {@code <Type>Gen} per annotated type, originating from that type. */
    private static Path isolatingProcessor(Path dir) throws IOException {
        Path procDir = dir.resolve("proc-iso");
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
        registerProcessor(procDir, "gen.GenProc");
        return procDir;
    }

    /** Aggregating: one {@code app.Registry} originating from ALL annotated types. */
    private static Path aggregatingProcessor(Path dir) throws IOException {
        Path procDir = dir.resolve("proc-agg");
        compile(
                procDir,
                Map.of(
                        "reg.Reg", """
                        package reg;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE)
                        public @interface Reg {}
                        """,
                        "reg.RegProc", """
                        package reg;
                        import javax.annotation.processing.*;
                        import javax.lang.model.SourceVersion;
                        import javax.lang.model.element.*;
                        import javax.tools.JavaFileObject;
                        import java.io.*;
                        import java.util.*;
                        @SupportedAnnotationTypes("reg.Reg")
                        public class RegProc extends AbstractProcessor {
                            public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                            public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) {
                                List<Element> all = new ArrayList<>(r.getElementsAnnotatedWith(Reg.class));
                                if (all.isEmpty()) return true;
                                try {
                                    JavaFileObject f = processingEnv.getFiler().createSourceFile(
                                            "app.Registry", all.toArray(new Element[0]));
                                    try (Writer w = f.openWriter()) {
                                        w.write("package app; public class Registry {}\\n");
                                    }
                                } catch (IOException ex) { throw new UncheckedIOException(ex); }
                                return true;
                            }
                        }
                        """));
        registerProcessor(procDir, "reg.RegProc");
        return procDir;
    }

    private static void registerProcessor(Path procDir, String impl) throws IOException {
        Files.writeString(
                Files.createDirectories(procDir.resolve("META-INF/services"))
                        .resolve("javax.annotation.processing.Processor"),
                impl + "\n");
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
}
