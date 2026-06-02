// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.repo.RepoCredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepoCommandTest {

    private static int run(String stdin, String... args) {
        InputStream prev = System.in;
        try {
            System.setIn(new ByteArrayInputStream(
                    (stdin == null ? "" : stdin).getBytes(StandardCharsets.UTF_8)));
            return Jk.execute(args);
        } finally {
            System.setIn(prev);
        }
    }

    @Test
    void login_stores_bearer_token_from_stdin(@TempDir Path dir) {
        int code = run("tok-from-stdin\n",
                "repo", "login", "corp-nexus", "--credentials-dir", dir.toString());
        assertThat(code).isEqualTo(0);
        assertThat(new RepoCredentialStore(dir).read("corp-nexus"))
                .contains(new RepoCredential.Bearer("tok-from-stdin"));
    }

    @Test
    void login_with_username_stores_basic(@TempDir Path dir) {
        int code = run("the-password\n",
                "repo", "login", "corp-nexus", "--username", "deployer",
                "--credentials-dir", dir.toString());
        assertThat(code).isEqualTo(0);
        assertThat(new RepoCredentialStore(dir).read("corp-nexus"))
                .contains(new RepoCredential.Basic("deployer", "the-password"));
    }

    @Test
    void blank_stdin_is_an_error(@TempDir Path dir) {
        int code = run("   \n", "repo", "login", "x", "--credentials-dir", dir.toString());
        assertThat(code).isEqualTo(1);
        assertThat(new RepoCredentialStore(dir).read("x")).isEmpty();
    }

    @Test
    void logout_removes_stored_credentials(@TempDir Path dir) {
        run("tok\n", "repo", "login", "corp-nexus", "--credentials-dir", dir.toString());
        int code = run(null, "repo", "logout", "corp-nexus", "--credentials-dir", dir.toString());
        assertThat(code).isEqualTo(0);
        assertThat(new RepoCredentialStore(dir).read("corp-nexus")).isEmpty();
    }
}
