// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CasTest {

    @Test
    void put_then_read_round_trip(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);
        Path path = cas.put(payload);

        assertThat(path).exists();
        assertThat(Files.readAllBytes(path)).isEqualTo(payload);
        assertThat(path.startsWith(tempDir.resolve("sha256"))).isTrue();
    }

    @Test
    void putStream_keys_by_content_hash_and_round_trips(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);

        Cas.Stored stored = cas.putStream(new java.io.ByteArrayInputStream(payload));

        // Same key and bytes as the buffered put() — streaming must not change the hash.
        assertThat(stored.path()).isEqualTo(cas.put(payload));
        assertThat(stored.sha256())
                .isEqualTo("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
        assertThat(stored.size()).isEqualTo(payload.length);
        assertThat(Files.readAllBytes(stored.path())).isEqualTo(payload);
    }

    @Test
    void putStream_is_idempotent(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        Cas.Stored a = cas.putStream(new java.io.ByteArrayInputStream(payload));
        Cas.Stored b = cas.putStream(new java.io.ByteArrayInputStream(payload));
        assertThat(a.path()).isEqualTo(b.path());
        // No leftover temp files from the second (discarded) write.
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.filter(p -> p.getFileName().toString().startsWith(".put-")))
                    .isEmpty();
        }
    }

    @Test
    void path_layout_uses_two_two_then_rest(@TempDir Path tempDir) {
        Cas cas = new Cas(tempDir);
        // 'hello world' SHA-256 = b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
        Path p = cas.pathFor("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
        assertThat(p).isEqualTo(tempDir.resolve("sha256/b9/4d/27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"));
    }

    @Test
    void put_is_idempotent(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        Path a = cas.put(payload);
        Path b = cas.put(payload);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void read_detects_corruption(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        byte[] payload = "trust me".getBytes(StandardCharsets.UTF_8);
        Path path = cas.put(payload);
        // Mutate the on-disk blob.
        Files.writeString(path, "tampered");

        // We must look it up by the original hash to trigger verification.
        String originalHex = path.getFileName().toString();
        String expected = path.getParent().getParent().getFileName().toString()
                + path.getParent().getFileName().toString()
                + originalHex;
        assertThatThrownBy(() -> cas.read(expected))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CAS corruption");
    }
}
