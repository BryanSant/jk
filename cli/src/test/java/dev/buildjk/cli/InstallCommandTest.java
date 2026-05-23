// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InstallCommandTest {

    @Test
    void splits_url_ref_at_hash_suffix() {
        var s = InstallCommand.splitUrlRef("https://github.com/foo/bar#v1.2.3");
        assertThat(s.url()).isEqualTo("https://github.com/foo/bar");
        assertThat(s.ref()).isEqualTo("v1.2.3");
    }

    @Test
    void splits_url_ref_at_trailing_at_suffix() {
        var s = InstallCommand.splitUrlRef("https://github.com/foo/bar@v1.2.3");
        assertThat(s.url()).isEqualTo("https://github.com/foo/bar");
        assertThat(s.ref()).isEqualTo("v1.2.3");
    }

    @Test
    void preserves_scp_form_at_in_url() {
        // `git@github.com:foo/bar` — the `@` is auth, not a ref separator.
        var s = InstallCommand.splitUrlRef("git@github.com:foo/bar");
        assertThat(s.url()).isEqualTo("git@github.com:foo/bar");
        assertThat(s.ref()).isNull();
    }

    @Test
    void splits_scp_form_when_ref_is_appended() {
        var s = InstallCommand.splitUrlRef("git@github.com:foo/bar@v1.2.3");
        assertThat(s.url()).isEqualTo("git@github.com:foo/bar");
        assertThat(s.ref()).isEqualTo("v1.2.3");
    }

    @Test
    void splits_shorthand_with_hash_ref() {
        var s = InstallCommand.splitUrlRef("gh:foo/bar#main");
        assertThat(s.url()).isEqualTo("gh:foo/bar");
        assertThat(s.ref()).isEqualTo("main");
    }

    @Test
    void no_args_with_no_jk_toml_returns_usage_error(@TempDir Path tempDir) {
        int exit = Jk.execute("install", "-C", tempDir.toString(),
                "--home", tempDir.resolve("home").toString());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void no_args_in_library_project_returns_usage_error(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                artifact = "lib-only"
                version  = "0.1.0"
                """);
        int exit = Jk.execute("install", "-C", tempDir.toString(),
                "--home", tempDir.resolve("home").toString());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // POSIX launcher only.
    void no_args_installs_current_project_after_building(@TempDir Path tempDir) throws Exception {
        // Scaffold a tiny runnable project.
        Jk.execute("init",
                "--group", "com.example",
                "--name", "widget",
                "--main", "com.example.App",
                tempDir.toString());
        Path src = tempDir.resolve("src/main/java/com/example/App.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package com.example;
                public final class App {
                    public static void main(String[] args) {}
                }
                """);

        // No source argument — install builds and writes a launcher named after the artifact.
        int exit = Jk.execute("install",
                "-C", tempDir.toString(),
                "--home", tempDir.resolve("home").toString());
        assertThat(exit).isEqualTo(0);

        Path launcher = tempDir.resolve("home/bin/widget");
        Path envJson = tempDir.resolve("home/tools/envs/widget/env.json");
        assertThat(launcher).exists();
        assertThat(envJson).exists();
        assertThat(Files.readString(launcher)).contains("com.example.App");
    }
}
