// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JavaCompileStrategy;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.compile.incremental.IncrementalCompiler;
import dev.jkbuild.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the incremental seam end-to-end with a fake {@link IncrementalCompiler}
 * + spy {@link JavaCompileStrategy} (no real javac): carry-over from the CAS,
 * recompiling only changed units, dropping a removed source's classes, and the
 * whole-phase cache-hit fast path. The bundled default path is covered by the
 * existing build/test suites.
 */
class IncrementalCompileTest {

    @Test
    void incremental_recompiles_only_changed_carries_over_and_drops(@TempDir Path tmp) throws IOException {
        Cas cas = new Cas(tmp.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tmp.resolve("actions"));
        Path src = tmp.resolve("src");
        Path out = tmp.resolve("out");
        Files.createDirectories(src);
        Path a = src.resolve("A.java");
        Path b = src.resolve("B.java");
        Files.writeString(a, "A1");
        Files.writeString(b, "B1");

        SpyStrategy spy = new SpyStrategy();
        FakeIncremental fake = new FakeIncremental();
        String task = "compile-main@mod";

        // Run 1 (no prior) → full compile of A and B.
        var r1 = run(task, req(List.of(a, b), out), cas, cache, fake, spy);
        assertThat(r1.success()).isTrue();
        assertThat(spy.lastCall()).containsExactlyInAnyOrder("A", "B");
        assertThat(out.resolve("A.class")).exists();
        assertThat(out.resolve("B.class")).exists();

        // Edit A; wipe the output dir to prove carry-over comes from the CAS.
        Files.writeString(a, "A2");
        deleteRecursively(out);

        // Run 2 → only A recompiled; B carried over from CAS untouched.
        var r2 = run(task, req(List.of(a, b), out), cas, cache, fake, spy);
        assertThat(r2.success()).isTrue();
        assertThat(spy.lastCall()).containsExactly("A");          // B was NOT recompiled
        assertThat(Files.readString(out.resolve("A.class"))).isEqualTo("A2"); // fresh
        assertThat(Files.readString(out.resolve("B.class"))).isEqualTo("B1"); // carried over
        var rec2 = cache.lastFor(task).orElseThrow();
        assertThat(rec2.outputs()).containsOnlyKeys("A.class", "B.class");
        assertThat(rec2.units()).containsOnlyKeys(a.toString(), b.toString());

        // Remove B → its class is dropped; A unchanged so nothing recompiles.
        Files.delete(b);
        var r3 = run(task, req(List.of(a), out), cas, cache, fake, spy);
        assertThat(r3.success()).isTrue();
        assertThat(spy.lastCall()).isEmpty();                     // nothing recompiled
        assertThat(out.resolve("B.class")).doesNotExist();        // dropped
        assertThat(out.resolve("A.class")).exists();
        assertThat(cache.lastFor(task).orElseThrow().outputs()).containsOnlyKeys("A.class");
    }

    @Test
    void unchanged_inputs_hit_the_phase_fast_path(@TempDir Path tmp) throws IOException {
        Cas cas = new Cas(tmp.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tmp.resolve("actions"));
        Path src = tmp.resolve("src");
        Files.createDirectories(src);
        Path a = src.resolve("A.java");
        Files.writeString(a, "A1");
        Path out = tmp.resolve("out");

        SpyStrategy spy = new SpyStrategy();
        FakeIncremental fake = new FakeIncremental();

        run("t@m", req(List.of(a), out), cas, cache, fake, spy);
        spy.calls.clear();
        // Identical inputs → whole-phase key hit → restore, no compile, no plan.
        var hit = run("t@m", req(List.of(a), out), cas, cache, fake, spy);
        assertThat(hit.cacheHit()).isTrue();
        assertThat(spy.calls).isEmpty();
        assertThat(out.resolve("A.class")).exists();
    }

    // --- helpers -----------------------------------------------------------

    private static IncrementalCompile.Result run(
            String task, CompileRequest req, Cas cas, ActionCache cache,
            IncrementalCompiler compiler, JavaCompileStrategy strategy) throws IOException {
        return IncrementalCompile.run(task, req, "v1", true, cas, cache,
                compiler, new JavacDriver(strategy));
    }

    private static CompileRequest req(List<Path> sources, Path out) {
        return CompileRequest.builder().sources(sources).outputDir(out).release(25).build();
    }

    /** Writes one {@code <base>.class} per source whose content mirrors the source. */
    static final class SpyStrategy implements JavaCompileStrategy {
        final List<List<String>> calls = new ArrayList<>();
        public String name() { return "spy"; }
        public CompileResult compile(CompileRequest req) throws IOException {
            List<String> names = new ArrayList<>();
            Files.createDirectories(req.outputDir());
            for (Path s : req.sources()) {
                String base = base(s);
                Files.writeString(req.outputDir().resolve(base + ".class"), Files.readString(s));
                names.add(base);
            }
            calls.add(names);
            return new CompileResult(true, List.of());
        }
        List<String> lastCall() { return calls.get(calls.size() - 1); }
    }

    /** Recompiles content-changed/new sources, carries the rest over, drops removed. */
    static final class FakeIncremental implements IncrementalCompiler {
        public String name() { return "fake"; }
        public CompilePlan plan(PlanRequest r) {
            List<Path> sources = r.request().sources();
            if (r.prior().isEmpty()) {
                return new CompilePlan(List.copyOf(sources), Set.of(), Set.of());
            }
            Map<String, String> priorInputs = r.prior().get().inputs();
            List<Path> recompile = new ArrayList<>();
            Set<Path> carryOver = new HashSet<>();
            for (Path s : sources) {
                String keyPath = s.toAbsolutePath().normalize().toString();
                String prevSha = priorInputs.get(keyPath);
                if (prevSha != null && prevSha.equals(sha(s))) carryOver.add(s);
                else recompile.add(s);
            }
            Set<Path> current = new HashSet<>(sources);
            Set<Path> dropped = new HashSet<>();
            for (Path priorSource : r.prior().get().unitsBySource().keySet()) {
                if (!current.contains(priorSource)) dropped.add(priorSource);
            }
            return new CompilePlan(recompile, carryOver, dropped);
        }
        public UnitOutputs attribute(CompilePlan plan, Path outputDir) {
            Map<Path, List<String>> by = new java.util.LinkedHashMap<>();
            for (Path s : plan.recompile()) by.put(s, List.of(base(s) + ".class"));
            return new UnitOutputs(by);
        }
    }

    private static String base(Path javaSource) {
        return javaSource.getFileName().toString().replace(".java", "");
    }

    private static String sha(Path file) {
        try {
            return Hashing.sha256Hex(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
    }
}
