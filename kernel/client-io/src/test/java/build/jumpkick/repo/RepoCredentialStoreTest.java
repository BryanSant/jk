// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import build.jumpkick.credential.RepoCredential;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoCredentialStoreTest {

    @Test
    void round_trips_bearer_and_basic(@TempDir Path dir) {
        var store = new RepoCredentialStore(dir);
        store.write("ghp", new RepoCredential.Bearer("tok-123"));
        store.write("corp-nexus", new RepoCredential.Basic("deployer", "p@ss w/ space"));

        assertThat(store.read("ghp")).contains(new RepoCredential.Bearer("tok-123"));
        assertThat(store.read("corp-nexus")).contains(new RepoCredential.Basic("deployer", "p@ss w/ space"));
        assertThat(store.read("absent")).isEmpty();
    }

    @Test
    void clear_removes_only_that_repo(@TempDir Path dir) {
        var store = new RepoCredentialStore(dir);
        store.write("a", new RepoCredential.Bearer("ta"));
        store.write("b", new RepoCredential.Bearer("tb"));
        store.clear("a");
        assertThat(store.read("a")).isEmpty();
        assertThat(store.read("b")).contains(new RepoCredential.Bearer("tb"));
    }

    @Test
    void refuses_to_store_anonymous(@TempDir Path dir) {
        assertThatThrownBy(() -> new RepoCredentialStore(dir).write("x", RepoCredential.ANONYMOUS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void file_is_owner_only_on_posix(@TempDir Path dir) throws Exception {
        var store = new RepoCredentialStore(dir);
        store.write("ghp", new RepoCredential.Bearer("tok"));
        Path file = dir.resolve("ghp");
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        assumeTrue(posix != null, "POSIX-only assertion");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }
}
