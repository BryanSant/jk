// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.KotlincRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The action-cache fast path of {@link KotlinCompile}: an exact-input hit
 * restores the output dir from the CAS and never forks the worker (so this runs
 * with no Kotlin toolchain present). The miss/worker path is covered end-to-end
 * by the CLI's KotlinCompilationTest.
 */
class KotlinCompileTest {

    @Test
    void restores_from_cache_on_exact_input_hit(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, dir.resolve("actions"));

        Path src = write(dir.resolve("A.kt"), "package x\nclass A");
        Path out = dir.resolve("out");
        // A bogus worker classpath — proves it's never launched on a hit.
        Path worker = write(dir.resolve("worker.jar"), "not a real jar");
        KotlincRequest req = req(src, out, worker);

        // Seed the cache: store a record under this request's key with one output.
        String key = ActionKey.forKotlinc("compile-kotlin", req, "jk-test");
        Path blob = cas.put("CLASS BYTES".getBytes(StandardCharsets.UTF_8));
        String sha = cas.hashFromPath(blob).orElseThrow();
        cache.storeWithOutputs("compile-kotlin", key, Map.of(), Map.of("x/A.class", sha));

        KotlinCompile.Result r = KotlinCompile.run("compile-kotlin", req, "jk-test", /*useCache=*/ true, cas, cache);

        assertThat(r.success()).isTrue();
        assertThat(r.cacheHit()).isTrue();
        assertThat(r.actionKey()).isEqualTo(key);
        // Output restored from the CAS, no compile.
        assertThat(out.resolve("x/A.class"))
                .usingCharset(StandardCharsets.UTF_8)
                .hasContent("CLASS BYTES");
    }

    @Test
    void key_changes_when_a_source_changes(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("out");
        Path worker = write(dir.resolve("worker.jar"), "stub");
        Path src = write(dir.resolve("A.kt"), "package x\nclass A");
        String k1 = ActionKey.forKotlinc("t", req(src, out, worker), "jk");
        write(src, "package x\nclass A { fun f() = 1 }");
        String k2 = ActionKey.forKotlinc("t", req(src, out, worker), "jk");
        assertThat(k2).isNotEqualTo(k1);
    }

    private static KotlincRequest req(Path src, Path out, Path worker) {
        return KotlincRequest.builder()
                .sources(List.of(src))
                .classpath(List.of())
                .outputDir(out)
                .jvmTarget(21)
                .workerClasspath(List.of(worker))
                .javaHome(Path.of(System.getProperty("java.home")))
                .build();
    }

    private static Path write(Path file, String body) throws IOException {
        Files.writeString(file, body);
        return file;
    }
}
