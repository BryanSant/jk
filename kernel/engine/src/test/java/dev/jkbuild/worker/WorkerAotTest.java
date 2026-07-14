// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerAotTest {

    @TempDir
    Path tmp;

    // ---- keying -----------------------------------------------------------------------------

    @Test
    void effective_gc_reads_bare_and_launcher_prefixed_flags() {
        assertThat(WorkerAot.effectiveGc(List.of("-XX:MaxRAMPercentage=25", "-XX:+UseZGC")))
                .isEqualTo("zgc");
        assertThat(WorkerAot.effectiveGc(List.of("-J-XX:+UseG1GC", "-J-Xmx1g"))).isEqualTo("g1gc");
        assertThat(WorkerAot.effectiveGc(List.of("-J-XX:+UseSerialGC"))).isEqualTo("serialgc");
        assertThat(WorkerAot.effectiveGc(List.of("-Xmx1g"))).isEqualTo("default");
    }

    @Test
    void key_changes_with_gc_and_classpath_but_is_stable_otherwise() throws IOException {
        WorkerAot.JdkId id = new WorkerAot.JdkId(tmp.resolve("jdk"), dev.jkbuild.jdk.JdkVendor.TEMURIN, "25.0.3");
        String base = WorkerAot.key(id, "zgc", "");
        assertThat(WorkerAot.key(id, "zgc", "")).isEqualTo(base);
        assertThat(WorkerAot.key(id, "g1", "")).isNotEqualTo(base);
        assertThat(WorkerAot.key(id, "zgc", "a.jar:b.jar")).isNotEqualTo(base);
        assertThat(base).hasSize(16);
    }

    @Test
    void jdk_id_parses_the_release_file_and_eligibility_gates_on_feature_and_vendor() throws IOException {
        Path jdk = Files.createDirectories(tmp.resolve("jdk25"));
        Files.writeString(jdk.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"25.0.3\"\n");
        WorkerAot.JdkId id = WorkerAot.jdkId(jdk);
        assertThat(id).isNotNull();
        assertThat(id.version()).isEqualTo("25.0.3");
        assertThat(WorkerAot.eligible(id)).isTrue();

        Path old = Files.createDirectories(tmp.resolve("jdk21"));
        Files.writeString(old.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"21.0.2\"\n");
        assertThat(WorkerAot.eligible(WorkerAot.jdkId(old))).isFalse();

        Path graal = Files.createDirectories(tmp.resolve("graal25"));
        Files.writeString(graal.resolve("release"), "IMPLEMENTOR=\"Oracle Corporation\"\nIMPLEMENTOR_VERSION=\"Oracle GraalVM 25\"\nJAVA_VERSION=\"25\"\n");
        WorkerAot.JdkId graalId = WorkerAot.jdkId(graal);
        if (graalId.vendor() == dev.jkbuild.jdk.JdkVendor.ORACLE_GRAALVM) {
            assertThat(WorkerAot.eligible(graalId)).isFalse();
        }

        assertThat(WorkerAot.jdkId(tmp.resolve("no-such-jdk"))).isNull(); // no release file
    }

    // ---- training lifecycle -------------------------------------------------------------------

    private static void waitUntil(Duration timeout, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within " + timeout);
            Thread.sleep(10);
        }
    }

    @Test
    void publish_keeps_the_n_most_recently_used_caches_and_expires_dead_keys() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("aot"));
        long day = 24L * 60 * 60 * 1_000;
        // Five live-ish keys with staggered last-use ages (1..5 days) — several keys are
        // legitimately live at once (different toolchain JDKs / Kotlin versions / GC pins).
        Path[] stale = new Path[5];
        for (int i = 0; i < 5; i++) {
            stale[i] = Files.writeString(dir.resolve("javac-stalekey000000" + i + "00.aot"), "old" + i);
            Files.setLastModifiedTime(
                    stale[i], java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - (i + 1) * day));
        }
        // A key untouched for 40 days is dead regardless of count; an orphaned failure marker
        // that old gets a fresh training chance; a young orphan marker stays sticky.
        Path dead = Files.writeString(dir.resolve("javac-deadkey000000000a.aot"), "dead");
        Files.setLastModifiedTime(dead, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 40 * day));
        Path deadMarker = Files.writeString(dir.resolve("javac-failkey000000000b.aot.noaot"), "");
        Files.setLastModifiedTime(
                deadMarker, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 40 * day));
        Path freshMarker = Files.writeString(dir.resolve("javac-failkey000000000c.aot.noaot"), "");
        // Engine caches share the directory but are version-lifecycle-owned — never swept here.
        Path engine = Files.writeString(dir.resolve("engine-1.0.0-aaaaaaaaaaaaaaaa.aot"), "engine");
        Files.setLastModifiedTime(engine, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 400 * day));

        Path cache = dir.resolve("javac-newkey0000000000.aot");
        // A stand-in trainer: any command that writes the aot output and exits 0.
        WorkerAot.trainAsync("test", cache, (aotOutput, scratch) ->
                List.of("bash", "-c", "echo trained > '" + aotOutput + "'"));
        waitUntil(Duration.ofSeconds(10), () -> Files.exists(cache));
        waitUntil(Duration.ofSeconds(10), () -> !Files.exists(stale[4]));

        // Keep 4 by recency: the new cache + the 3 youngest stale keys; the rest reclaimed.
        assertThat(stale[0]).exists();
        assertThat(stale[1]).exists();
        assertThat(stale[2]).exists();
        assertThat(stale[3]).doesNotExist();
        assertThat(dead).doesNotExist();
        assertThat(deadMarker).doesNotExist();
        assertThat(freshMarker).exists();
        assertThat(engine).exists();
        assertThat(Files.exists(cache.resolveSibling(cache.getFileName() + ".training")))
                .isFalse(); // claim released
    }

    @Test
    void failed_training_leaves_a_sticky_noaot_marker_instead_of_a_cache() throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("aot2")).resolve("javac-failkey000000000.aot");
        WorkerAot.trainAsync("test", cache, (aotOutput, scratch) -> List.of("bash", "-c", "exit 1"));
        waitUntil(Duration.ofSeconds(10), () -> Files.exists(WorkerAot.noaotMarker(cache)));
        assertThat(cache).doesNotExist();
    }

    @Test
    void a_fresh_claim_file_from_another_process_blocks_training() throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("aot3")).resolve("javac-claimed000000000.aot");
        Files.createFile(cache.resolveSibling(cache.getFileName() + ".training")); // fresh foreign claim
        WorkerAot.trainAsync("test", cache, (aotOutput, scratch) ->
                List.of("bash", "-c", "echo trained > '" + aotOutput + "'"));
        Thread.sleep(300); // trainAsync claims synchronously; nothing should have been spawned
        assertThat(cache).doesNotExist();
    }
}
