// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jk new} / {@code jk init} member-awareness: detecting an enclosing
 * project, inheriting its settings, and registering the new directory as a
 * workspace member (promoting a plain project into a workspace on its first
 * member). The non-TTY flag path is exercised here; the wizard UX shares the
 * same {@code parent} resolution.
 */
class NewMemberTest {

    private static final String PLAIN_PROJECT = """
            [project]
            group    = "com.acme"
            name     = "root"
            version  = "0.1.0"
            jdk      = 17
            java     = 17
            """;

    @Test
    void adds_member_to_a_plain_project_and_promotes_it_to_a_workspace(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), PLAIN_PROJECT);
        Path member = tempDir.resolve("widget");

        int exit = Jk.execute("new", member.toString());
        assertThat(exit).isZero();

        // Member scaffolded, no per-member lock (the root owns resolution).
        assertThat(member.resolve("jk.toml")).exists();
        assertThat(member.resolve("jk.lock")).doesNotExist();

        // Group + JDK/java release inherited from the parent.
        JkBuild m = JkBuildParser.parse(member.resolve("jk.toml"));
        assertThat(m.project().group()).isEqualTo("com.acme");
        assertThat(m.project().name()).isEqualTo("widget");
        assertThat(m.project().javaRelease()).isEqualTo(17);

        // The plain parent was promoted to a workspace root with the member.
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(root.isWorkspaceRoot()).isTrue();
        assertThat(root.workspace().members()).containsExactly("widget");
    }

    @Test
    void member_inherits_a_divergent_java_release_from_the_parent(@TempDir Path tempDir) throws IOException {
        // Parent runs JDK 25 but targets release 17 — the member must inherit
        // both, even though the wizard never exposes the release choice.
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.acme"
                name     = "root"
                version  = "0.1.0"
                jdk      = 25
                java     = 17
                """);
        Path member = tempDir.resolve("widget");

        assertThat(Jk.execute("new", member.toString())).isZero();

        String toml = Files.readString(member.resolve("jk.toml"));
        assertThat(toml).contains("jdk      = 25");   // toolchain inherited
        assertThat(toml).contains("java     = 17");   // compile target flowed through
        assertThat(JkBuildParser.parse(member.resolve("jk.toml")).project().javaRelease()).isEqualTo(17);
    }

    @Test
    void member_inherits_kotlin_language(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.acme"
                name     = "root"
                version  = "0.1.0"
                jdk      = 25
                kotlin   = "2.3.21"
                """);
        Path member = tempDir.resolve("k");

        assertThat(Jk.execute("new", member.toString())).isZero();
        assertThat(JkBuildParser.parse(member.resolve("jk.toml")).project().isKotlin()).isTrue();
    }

    @Test
    void no_member_creates_a_standalone_project_inside_a_project(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), PLAIN_PROJECT);
        Path sub = tempDir.resolve("widget");

        int exit = Jk.execute("new", "--no-member", "--group", "com.solo", sub.toString());
        assertThat(exit).isZero();

        // Standalone: has its own lock, its own group, parent untouched.
        assertThat(sub.resolve("jk.lock")).exists();
        assertThat(JkBuildParser.parse(sub.resolve("jk.toml")).project().group()).isEqualTo("com.solo");
        assertThat(JkBuildParser.parse(tempDir.resolve("jk.toml")).isWorkspaceRoot()).isFalse();
    }

    // ---- detectParentDir boundary logic -------------------------------------

    @Test
    void detect_finds_jk_toml_in_start_dir(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), PLAIN_PROJECT);
        assertThat(NewCommand.detectParentDir(dir, null, false)).contains(dir);
    }

    @Test
    void detect_finds_jk_toml_in_an_ancestor(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), PLAIN_PROJECT);
        Path deep = dir.resolve("a/b");
        Files.createDirectories(deep);
        assertThat(NewCommand.detectParentDir(deep, null, false)).contains(dir);
    }

    @Test
    void detect_stops_at_a_git_boundary(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), PLAIN_PROJECT);   // project above the repo
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));              // git root with no jk.toml
        Path start = repo.resolve("sub");
        Files.createDirectories(start);
        // Walking up hits repo/.git before the project above — standalone.
        assertThat(NewCommand.detectParentDir(start, null, false)).isEmpty();
    }

    @Test
    void detect_stops_at_home_boundary(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), PLAIN_PROJECT);
        Path home = dir.resolve("home");
        Path start = home.resolve("sub");
        Files.createDirectories(start);
        assertThat(NewCommand.detectParentDir(start, home, false)).isEmpty();
    }

    @Test
    void detect_respects_no_member_flag(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), PLAIN_PROJECT);
        assertThat(NewCommand.detectParentDir(dir, null, /* noMember */ true)).isEmpty();
    }

    @Test
    void detect_returns_empty_with_no_enclosing_project(@TempDir Path dir) {
        assertThat(NewCommand.detectParentDir(dir, null, false)).isEmpty();
    }

    // ---- jdkFloor: the Java Language Version shapes the JDK list ------------

    @Test
    void jdk_floor_uses_the_chosen_java_version() {
        assertThat(NewCommand.jdkFloor(answers("lang", "java", "javaVersion", "25"), null)).isEqualTo(25);
        assertThat(NewCommand.jdkFloor(answers("lang", "java", "javaVersion", "17"), null)).isEqualTo(17);
    }

    @Test
    void jdk_floor_is_unrestricted_for_kotlin() {
        assertThat(NewCommand.jdkFloor(answers("lang", "kotlin"), null)).isZero();
    }

    @Test
    void jdk_floor_defaults_to_latest_lts_when_unanswered() {
        assertThat(NewCommand.jdkFloor(answers("lang", "java"), null))
                .isEqualTo(NewCommand.LATEST_LTS_MAJOR);
    }

    private static dev.jkbuild.cli.tui.Answers answers(String... kv) {
        var map = new java.util.HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return dev.jkbuild.cli.tui.Answers.of(map);
    }
}
