// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic coverage of the resolved-closure cache (the warm-build fast
 * path). The Maven resolution itself is network-bound and exercised end-to-end
 * by the higher-level Kotlin build tests once the worker is wired in.
 */
class KotlinBtaResolverTest {

    @Test
    void round_trips_a_recorded_closure(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cache"));
        List<String> shas = seedBlobs(cas, "alpha", "beta", "gamma");
        Path cacheFile = dir.resolve("closure.shas");

        KotlinBtaResolver.writeCachedClosure(cacheFile, shas);
        List<Path> jars = KotlinBtaResolver.readCachedClosure(cacheFile, cas);

        assertThat(jars).isNotNull();
        assertThat(jars).hasSize(3);
        // Each recorded hash maps back to its CAS path, in order.
        for (int i = 0; i < shas.size(); i++) {
            assertThat(jars.get(i)).isEqualTo(cas.pathFor(shas.get(i)));
            assertThat(jars.get(i)).isRegularFile();
        }
    }

    @Test
    void absent_cache_file_is_a_miss(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cache"));
        assertThat(KotlinBtaResolver.readCachedClosure(dir.resolve("nope.shas"), cas)).isNull();
    }

    @Test
    void evicted_blob_invalidates_the_whole_closure(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cache"));
        List<String> shas = seedBlobs(cas, "one", "two");
        Path cacheFile = dir.resolve("closure.shas");
        KotlinBtaResolver.writeCachedClosure(cacheFile, shas);

        // Evict one blob from the CAS — a partial closure is unusable, so the
        // whole thing must miss and force a fresh resolve.
        Files.delete(cas.pathFor(shas.get(0)));

        assertThat(KotlinBtaResolver.readCachedClosure(cacheFile, cas)).isNull();
    }

    @Test
    void blank_lines_are_ignored(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cache"));
        List<String> shas = seedBlobs(cas, "x");
        Path cacheFile = dir.resolve("closure.shas");
        Files.writeString(cacheFile, "\n" + shas.get(0) + "\n\n", StandardCharsets.UTF_8);

        assertThat(KotlinBtaResolver.readCachedClosure(cacheFile, cas)).hasSize(1);
    }

    @Test
    void enforces_the_2_4_0_floor() {
        // Below the floor: the worker's KotlinToolchains entry point doesn't exist.
        for (String tooOld : List.of("2.3.21", "2.0.0", "1.9.24", "2.3.99")) {
            assertThatThrownBy(() -> KotlinBtaResolver.requireSupportedVersion(tooOld))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2.4.0");
        }
        // At or above the floor: accepted (incl. pre-release and future majors).
        for (String ok : List.of("2.4.0", "2.4.20", "2.5.0", "2.4.0-RC2", "3.0.0")) {
            assertThatCode(() -> KotlinBtaResolver.requireSupportedVersion(ok))
                    .doesNotThrowAnyException();
        }
    }

    private static List<String> seedBlobs(Cas cas, String... bodies) throws IOException {
        List<String> shas = new ArrayList<>();
        for (String body : bodies) {
            Path p = cas.put(body.getBytes(StandardCharsets.UTF_8));
            shas.add(cas.hashFromPath(p).orElseThrow());
        }
        return shas;
    }
}
