// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.engine.EngineClient.AotMode;
import dev.jkbuild.cli.engine.EngineClient.EngineArtifact;
import dev.jkbuild.cli.engine.EngineClient.EngineJdk;
import dev.jkbuild.cli.engine.EngineClient.EngineTarget;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.jdk.JdkVendor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pure AOT-cache decision logic: key derivation, mode selection, and log-scan detection. */
class EngineAotCacheTest {

    private static EngineJdk temurin(String version) {
        return new EngineJdk(Path.of("/opt/jdk"), JdkVendor.TEMURIN, version);
    }

    private static Path jar(Path dir, String name, String bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, bytes);
        return p;
    }

    @Test
    void key_is_stable_for_identical_inputs(@TempDir Path dir) throws IOException {
        EnginePaths.Paths paths = EnginePaths.resolve(dir);
        Files.createDirectories(paths.dir());
        Path jar = jar(dir, "jk-engine-1.jar", "aaa");
        assertThat(EngineClient.aotCachePath(paths, jar, temurin("25.0.3")))
                .isEqualTo(EngineClient.aotCachePath(paths, jar, temurin("25.0.3")));
    }

    @Test
    void key_changes_on_jar_jdk_version_and_vendor(@TempDir Path dir) throws IOException {
        EnginePaths.Paths paths = EnginePaths.resolve(dir);
        Files.createDirectories(paths.dir());
        Path jarA = jar(dir, "jk-engine-A.jar", "aaa");
        Path jarB = jar(dir, "jk-engine-B.jar", "bbbbb"); // different name + size

        Path base = EngineClient.aotCachePath(paths, jarA, temurin("25.0.3"));
        Path diffJar = EngineClient.aotCachePath(paths, jarB, temurin("25.0.3"));
        Path diffVersion = EngineClient.aotCachePath(paths, jarA, temurin("25.0.4"));
        Path diffVendor =
                EngineClient.aotCachePath(paths, jarA, new EngineJdk(Path.of("/opt/jdk"), JdkVendor.ORACLE_GRAALVM, "25.0.3"));
        Path noJdk = EngineClient.aotCachePath(paths, jarA, null);

        assertThat(base).isNotEqualTo(diffJar);
        assertThat(base).isNotEqualTo(diffVersion);
        assertThat(base).isNotEqualTo(diffVendor);
        assertThat(base).isNotEqualTo(noJdk);
        assertThat(base.getFileName().toString()).startsWith("engine-").endsWith(".aot");
    }

    @Test
    void stale_caches_and_markers_for_other_keys_are_swept(@TempDir Path dir) throws IOException {
        EnginePaths.Paths paths = EnginePaths.resolve(dir);
        Files.createDirectories(paths.dir());
        Path staleAot = paths.dir().resolve("engine-deadbeefdeadbeef.aot");
        Path staleMarker = paths.dir().resolve("engine-deadbeefdeadbeef.noaot");
        Files.writeString(staleAot, "old");
        Files.writeString(staleMarker, "");
        Path jar = jar(dir, "jk-engine-1.jar", "aaa");

        Path current = EngineClient.aotCachePath(paths, jar, temurin("25.0.3"));
        Path currentMarker = current.resolveSibling(
                current.getFileName().toString().replaceAll("\\.aot$", "") + ".noaot");
        // A marker for the CURRENT key must survive; pre-create it and confirm.
        Files.writeString(currentMarker, "");
        EngineClient.aotCachePath(paths, jar, temurin("25.0.3")); // second call performs the sweep again

        assertThat(staleAot).doesNotExist();
        assertThat(staleMarker).doesNotExist();
        assertThat(currentMarker).exists();
    }

    @Test
    void choose_mode_none_for_non_jar_non_hotspot_and_marker(@TempDir Path dir) throws IOException {
        Path aot = dir.resolve("engine-x.aot");
        Files.writeString(aot, "cache");
        EngineArtifact jar = new EngineArtifact(EngineArtifact.Kind.JAR, "j", "lib");
        EngineArtifact fallback = new EngineArtifact(EngineArtifact.Kind.FALLBACK, "jk", "fallback");

        assertThat(EngineClient.chooseAotMode(new EngineTarget(fallback, null, false, null, false)))
                .isEqualTo(AotMode.NONE); // non-JAR
        assertThat(EngineClient.chooseAotMode(new EngineTarget(jar, dir, false, aot, false)))
                .isEqualTo(AotMode.NONE); // GraalVM host
        assertThat(EngineClient.chooseAotMode(new EngineTarget(jar, dir, true, aot, true)))
                .isEqualTo(AotMode.NONE); // sticky .noaot marker
    }

    @Test
    void choose_mode_train_when_no_cache_use_when_present(@TempDir Path dir) throws IOException {
        EngineArtifact jar = new EngineArtifact(EngineArtifact.Kind.JAR, "j", "lib");
        Path missing = dir.resolve("engine-missing.aot");
        Path present = dir.resolve("engine-present.aot");
        Files.writeString(present, "cache");

        assertThat(EngineClient.chooseAotMode(new EngineTarget(jar, dir, true, missing, false)))
                .isEqualTo(AotMode.TRAIN);
        assertThat(EngineClient.chooseAotMode(new EngineTarget(jar, dir, true, present, false)))
                .isEqualTo(AotMode.USE);
    }

    @Test
    void log_scan_detects_aot_markers(@TempDir Path dir) throws IOException {
        assertThat(EngineClient.scanLogForAotError(write(dir, "a.log", "[0.0s][error][aot] boom\n"))).isTrue();
        assertThat(EngineClient.scanLogForAotError(
                        write(dir, "b.log", "Mismatched values for property jdk.module.addmods: ...\n")))
                .isTrue();
        assertThat(EngineClient.scanLogForAotError(write(dir, "c.log", "Disabling optimized module handling\n")))
                .isTrue();
        assertThat(EngineClient.scanLogForAotError(write(dir, "d.log", "jk engine: spawning ...\nready\n")))
                .isFalse();
        assertThat(EngineClient.scanLogForAotError(dir.resolve("missing.log"))).isFalse();
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }
}
