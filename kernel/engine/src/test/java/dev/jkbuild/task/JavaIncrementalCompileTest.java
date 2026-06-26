// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JavaCompileStrategies;
import dev.jkbuild.compile.JavaCompileStrategy;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.compile.incremental.JavacFixture;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end incremental Java compilation (real subprocess javac). A recording
 * strategy captures exactly which sources each pass compiled, so we can assert
 * the precise dirty set — the whole point of the multi-pass orchestrator.
 */
class JavaIncrementalCompileTest {

    @Test
    void body_only_edit_recompiles_only_that_source(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/B.java", "package a; public class B { public String greet() { return \"hi\"; } }");
        p.write("a/A.java", "package a; public class A { public String use() { return new B().greet(); } }");

        assertThat(p.build().compiledSources()).containsExactlyInAnyOrder("a/A.java", "a/B.java");

        // Change B's method *body* only — same ABI.
        p.write("a/B.java", "package a; public class B { public String greet() { return \"hello there\"; } }");
        Run r = p.build();
        assertThat(r.outcome).isEqualTo("compiled");
        assertThat(r.compiledSources()).containsExactly("a/B.java"); // A is NOT recompiled
    }

    @Test
    void abi_change_recompiles_dependents_via_the_dependency_graph(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        // A references B in bytecode (constructs it, calls greet()).
        p.write("a/B.java", "package a; public class B { public String greet() { return \"hi\"; } }");
        p.write("a/A.java", "package a; public class A { public String use() { return new B().greet(); } }");
        p.build();

        // ABI change to B (add a public method) — A doesn't use it, but A depends on B,
        // so A is conservatively recompiled via the reverse-dependency closure.
        p.write(
                "a/B.java",
                "package a; public class B { public String greet() { return \"hi\"; } public void bye() {} }");
        Run r = p.build();
        assertThat(r.compiledSources()).containsExactlyInAnyOrder("a/B.java", "a/A.java");
    }

    @Test
    void changing_an_inlined_constant_recompiles_dependents_conservatively(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        // B.X is a compile-time constant — javac inlines it into A, leaving no
        // bytecode edge, so the constant change must trigger a conservative recompile.
        p.write("a/B.java", "package a; public class B { public static final int X = 1; }");
        p.write("a/A.java", "package a; public class A { public static int v() { return B.X; } }");
        p.build();
        assertThat(invokeStaticInt(p.out, "a.A", "v")).isEqualTo(1);

        p.write("a/B.java", "package a; public class B { public static final int X = 2; }");
        Run r = p.build();
        assertThat(r.compiledSources()).contains("a/A.java"); // A recompiled despite no bytecode edge
        assertThat(invokeStaticInt(p.out, "a.A", "v")).isEqualTo(2); // and picked up the new value
    }

    @Test
    void dependency_abi_change_recompiles_referencing_sources(@TempDir Path dir) throws Exception {
        // Two versions of a dependency at different paths (a dependency bump). v2
        // adds an overload, an ABI change A's bytecode must react to.
        JavacFixture.compile(
                dir.resolve("depv1"),
                java.util.Map.of("dep.Lib", "package dep; public class Lib { public void f(Object o) {} }"));
        JavacFixture.compile(
                dir.resolve("depv2"),
                java.util.Map.of(
                        "dep.Lib",
                        "package dep; public class Lib { public void f(Object o) {} public void f(String s) {} }"));
        Path depV1 = dir.resolve("depv1").resolve("out");
        Path depV2 = dir.resolve("depv2").resolve("out");

        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A { public void call(dep.Lib lib) { lib.f(\"hi\"); } }");

        p.build(List.of(depV1)); // compile A against dep v1
        Run r = p.build(List.of(depV2)); // dep bumped to v2 (different classpath path)
        assertThat(r.compiledSources()).contains("a/A.java"); // A recompiled because dep/Lib's ABI changed
    }

    @Test
    void no_change_rebuild_is_an_action_cache_hit(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A { public int f() { return 1; } }");
        p.build();
        Run r = p.build(); // identical inputs
        assertThat(r.outcome).startsWith("cache-hit");
        assertThat(r.compiledSources()).isEmpty(); // nothing forked javac
    }

    @Test
    void removing_a_leaf_source_deletes_its_class_without_recompiling(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A { public int f() { return 1; } }");
        p.write("a/B.java", "package a; public class B { public int g() { return 2; } }"); // independent
        p.build();

        p.remove("a/B.java");
        Run r = p.build();
        assertThat(r.outcome).isEqualTo("compiled"); // incremental, not a full rebuild
        assertThat(r.compiledSources()).isEmpty(); // A untouched, B gone → nothing recompiled
        assertThat(p.classExists("a/B.class")).isFalse(); // the removed class is cleaned up
        assertThat(p.classExists("a/A.class")).isTrue();
    }

    @Test
    void removing_a_referenced_source_recompiles_the_consumer_and_surfaces_the_break(@TempDir Path dir)
            throws Exception {
        Project p = new Project(dir);
        p.write("a/B.java", "package a; public class B { public int g() { return 2; } }");
        p.write("a/A.java", "package a; public class A { public int f() { return new B().g(); } }");
        p.build();

        // Remove B but leave A referencing it: A must be recompiled (so the now-dangling
        // reference becomes a real compile error) rather than carried over stale.
        p.remove("a/B.java");
        Run r = p.tryBuild();
        assertThat(r.outcome).isEqualTo("errors");
        assertThat(r.compiledSources()).contains("a/A.java");
    }

    @Test
    void removing_a_constant_holder_recompiles_remaining_sources_conservatively(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        // B is a constant holder: consumers inline B.X with no bytecode edge, so its
        // removal can't be tracked precisely → recompile everything still present.
        p.write("a/B.java", "package a; public class B { public static final int X = 1; }");
        p.write("a/A.java", "package a; public class A { public int f() { return 5; } }"); // no edge to B
        p.write("a/C.java", "package a; public class C { public int g() { return 6; } }"); // no edge to B
        p.build();

        p.remove("a/B.java");
        Run r = p.build();
        assertThat(r.outcome).isEqualTo("compiled");
        assertThat(r.compiledSources()).containsExactlyInAnyOrder("a/A.java", "a/C.java");
        assertThat(p.classExists("a/B.class")).isFalse();
    }

    // ---- harness ----------------------------------------------------------

    private static final class Project {
        final Path root;
        final Path srcRoot;
        final Path out;
        final Cas cas;
        final ActionCache actionCache;
        final Path stateDir;

        Project(Path root) throws IOException {
            this.root = root;
            this.srcRoot = root.resolve("src/main/java");
            this.out = root.resolve("out");
            this.cas = new Cas(root.resolve("cas"));
            this.actionCache = new ActionCache(cas, root.resolve("actions"));
            this.stateDir = root.resolve("state");
            Files.createDirectories(srcRoot);
        }

        void write(String rel, String body) throws IOException {
            Path f = srcRoot.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, body);
        }

        void remove(String rel) throws IOException {
            Files.delete(srcRoot.resolve(rel));
        }

        boolean classExists(String rel) {
            return Files.exists(out.resolve(rel));
        }

        Run build() throws IOException {
            return build(List.of());
        }

        /** Build without asserting success — for tests that expect a compile error. */
        Run tryBuild() throws IOException {
            return build(List.of(), false);
        }

        Run build(List<Path> classpath) throws IOException {
            return build(classpath, true);
        }

        Run build(List<Path> classpath, boolean requireSuccess) throws IOException {
            List<Path> sources = new ArrayList<>();
            try (var s = Files.walk(srcRoot)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.toString().endsWith(".java")) sources.add(p);
                }
            }
            CompileRequest req = CompileRequest.builder()
                    .sources(sources)
                    .classpath(classpath)
                    .outputDir(out)
                    .release(21)
                    .extraOptions(List.of())
                    .javaHome(Path.of(System.getProperty("java.home")))
                    .build();
            Recording rec = new Recording(JavaCompileStrategies.resolve());
            JavaIncrementalCompile.Result result = JavaIncrementalCompile.run(
                    "compile-main", req, "jk-test", true, cas, actionCache, stateDir, new JavacDriver(rec), null);
            if (requireSuccess) {
                assertThat(result.success()).as("compile succeeded").isTrue();
            }
            return new Run(result.outcome(), rec.compiled());
        }
    }

    private record Run(String outcome, Set<String> compiled) {
        Set<String> compiledSources() {
            return compiled;
        }
    }

    /** Wraps the real strategy, recording the basenamed source paths of each compile pass. */
    private static final class Recording implements JavaCompileStrategy {
        private final JavaCompileStrategy delegate;
        private final Set<String> compiled = new LinkedHashSet<>();

        Recording(JavaCompileStrategy delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public CompileResult compile(CompileRequest request) throws IOException {
            for (Path s : request.sources()) {
                String n = s.toString().replace('\\', '/');
                int idx = n.indexOf("/src/main/java/");
                compiled.add(idx >= 0 ? n.substring(idx + "/src/main/java/".length()) : n);
            }
            return delegate.compile(request);
        }

        Set<String> compiled() {
            return compiled;
        }
    }

    private static int invokeStaticInt(Path out, String fqcn, String method) throws Exception {
        try (URLClassLoader cl = new URLClassLoader(new URL[] {out.toUri().toURL()}, null)) {
            return (int) Class.forName(fqcn, true, cl).getMethod(method).invoke(null);
        }
    }
}
