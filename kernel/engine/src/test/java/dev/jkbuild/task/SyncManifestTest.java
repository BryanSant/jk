// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncManifestTest {

    @Test
    void write_then_read_roundtrips(@TempDir Path tempDir) throws IOException {
        Path lockFile = tempDir.resolve("jk.lock");
        Files.writeString(lockFile, "stub");
        Lockfile lock = new Lockfile(
                1,
                "jk test",
                "pubgrub-v1",
                null,
                List.of(
                        new Lockfile.Artifact(
                                "com.foo:bar",
                                "1.0",
                                "central+https://...",
                                "sha256:aaaaaa",
                                null,
                                List.of(Scope.MAIN),
                                List.of()),
                        new Lockfile.Artifact(
                                "com.foo:baz",
                                "2.0",
                                "central+https://...",
                                "sha256:bbbbbb",
                                null,
                                List.of(Scope.MAIN),
                                List.of())));

        Path actionRoot = tempDir.resolve("actions");
        Path manifest = SyncManifest.write(actionRoot, lockFile, lock);

        var read = SyncManifest.read(manifest);
        assertThat(read).isPresent();
        var m = read.get();
        assertThat(m.projectFingerprint()).isEqualTo(Sweep.projectFingerprint(lockFile));
        assertThat(m.lockFile()).isEqualTo(lockFile.toAbsolutePath().normalize());
        assertThat(m.stampMillis()).isGreaterThan(0L);
        assertThat(m.refs()).containsExactlyInAnyOrder("aaaaaa", "bbbbbb");
    }

    @Test
    void packages_without_checksum_are_skipped(@TempDir Path tempDir) throws IOException {
        Path lockFile = tempDir.resolve("jk.lock");
        Files.writeString(lockFile, "stub");
        Lockfile lock = new Lockfile(
                1,
                "jk test",
                "pubgrub-v1",
                null,
                List.of(
                        new Lockfile.Artifact(
                                "com.foo:bar",
                                "1.0",
                                "central+https://...",
                                "sha256:aaaaaa",
                                null,
                                List.of(Scope.MAIN),
                                List.of()),
                        // POM-only / path-style — no checksum to root.
                        new Lockfile.Artifact(
                                "com.foo:nope",
                                "2.0",
                                "central+https://...",
                                null,
                                null,
                                List.of(Scope.MAIN),
                                List.of())));

        Path actionRoot = tempDir.resolve("actions");
        Path manifest = SyncManifest.write(actionRoot, lockFile, lock);

        assertThat(SyncManifest.read(manifest).get().refs()).containsExactly("aaaaaa");
    }

    @Test
    void fingerprint_changes_when_project_path_moves(@TempDir Path tempDir) throws IOException {
        Path a = tempDir.resolve("a/jk.lock");
        Path b = tempDir.resolve("b/jk.lock");
        Files.createDirectories(a.getParent());
        Files.createDirectories(b.getParent());
        Files.writeString(a, "stub");
        Files.writeString(b, "stub");
        assertThat(Sweep.projectFingerprint(a)).isNotEqualTo(Sweep.projectFingerprint(b));
    }
}
