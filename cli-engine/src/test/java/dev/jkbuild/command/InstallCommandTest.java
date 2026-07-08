// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.Jk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

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
        int exit = Jk.execute(
                "install",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.resolve("state").toString(),
                "--bin-dir",
                tempDir.resolve("bin").toString());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void library_project_does_cache_install_only(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "lib-only"
                version  = "0.1.0"
                jdk      = 25
                java     = 25
                """);
        Path src = tempDir.resolve("src/main/java/com/example/Lib.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com.example; public final class Lib {}\n");

        Path bin = tempDir.resolve("bin");
        Path cache = tempDir.resolve("cache");
        Path m2 = tempDir.resolve("m2");
        int exit = Jk.execute(
                "install",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                cache.toString(),
                "--state-dir",
                tempDir.resolve("state").toString(),
                "--bin-dir",
                bin.toString(),
                "--lib-dir",
                tempDir.resolve("lib").toString(),
                "--m2-dir",
                m2.toString());
        // A library is not a usage error any more — it cache-installs.
        assertThat(exit).isEqualTo(0);
        assertThat(bin.resolve("lib-only")).doesNotExist(); // no launcher
        // m2install defaults to false: repos/local/ is primary — the real jar lands there —
        // and ~/.m2 is untouched since this project didn't opt in.
        assertThat(cache.resolve("repos/local/com/example/lib-only/0.1.0/lib-only-0.1.0.jar"))
                .exists();
        assertThat(m2.resolve("repository/com/example/lib-only/0.1.0/lib-only-0.1.0.jar"))
                .doesNotExist();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // POSIX launcher only.
    void application_install_writes_lib_layout_and_launcher(@TempDir Path tempDir) throws Exception {
        Jk.execute(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--executable",
                "--layout",
                "traditional",
                tempDir.toString());
        Path src = tempDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package com.example;
                public final class Main {
                    public static void main(String[] args) {}
                }
                """);

        Path bin = tempDir.resolve("bin");
        Path lib = tempDir.resolve("lib");
        int exit = Jk.execute(
                "install",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.resolve("state").toString(),
                "--bin-dir",
                bin.toString(),
                "--lib-dir",
                lib.toString());
        assertThat(exit).isEqualTo(0);

        Path launcher = bin.resolve("widget");
        Path appJar = lib.resolve("widget-0.1.0.jar");
        assertThat(launcher).exists();
        assertThat(appJar).exists();
        String script = Files.readString(launcher);
        assertThat(script).contains("com.example.Main");
        assertThat(script).contains(appJar.toString()); // classpath points at lib
    }

    @Test
    void m2install_writes_jar_and_pom_to_local_maven_repo(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group     = "com.example"
                name      = "lib-only"
                version   = "0.1.0"
                jdk       = 25
                java      = 25
                m2install = true
                """);
        Path src = tempDir.resolve("src/main/java/com/example/Lib.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com.example; public final class Lib {}\n");

        Path m2 = tempDir.resolve("m2");
        int exit = Jk.execute(
                "install",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.resolve("state").toString(),
                "--bin-dir",
                tempDir.resolve("bin").toString(),
                "--lib-dir",
                tempDir.resolve("lib").toString(),
                "--m2-dir",
                m2.toString());
        assertThat(exit).isEqualTo(0);

        Path repo = m2.resolve("repository/com/example/lib-only/0.1.0");
        assertThat(repo.resolve("lib-only-0.1.0.jar")).exists();
        assertThat(repo.resolve("lib-only-0.1.0.pom")).exists();
    }
}
