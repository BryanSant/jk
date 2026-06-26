// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.forge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenStoreTest {

    @Test
    void round_trips_per_host(@TempDir Path dir) {
        var store = new TokenStore(dir);
        store.write("github.com", "gh-token");
        store.write("codeberg.org", "cb-token");

        assertThat(store.read("github.com")).contains("gh-token");
        assertThat(store.read("codeberg.org")).contains("cb-token");
        assertThat(store.read("gitlab.com")).isEmpty();
    }

    @Test
    void clear_removes_only_that_host(@TempDir Path dir) {
        var store = new TokenStore(dir);
        store.write("github.com", "gh-token");
        store.write("codeberg.org", "cb-token");

        store.clear("github.com");
        assertThat(store.read("github.com")).isEmpty();
        assertThat(store.read("codeberg.org")).contains("cb-token");
    }

    @Test
    void accepts_a_url_and_normalizes_to_host(@TempDir Path dir) {
        var store = new TokenStore(dir);
        store.write("https://github.com/owner/repo", "gh-token");
        // Reads back under the bare host.
        assertThat(store.read("github.com")).contains("gh-token");
    }

    @Test
    void token_file_is_owner_only_on_posix(@TempDir Path dir) throws Exception {
        var store = new TokenStore(dir);
        store.write("github.com", "gh-token");

        Path file = dir.resolve("github.com");
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        assumeTrue(posix != null, "POSIX-only assertion");

        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }
}
