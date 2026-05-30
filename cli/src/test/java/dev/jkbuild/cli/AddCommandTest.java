// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AddCommandTest {

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String member(String artifact, String version) {
        return """
                [project]
                group    = "dev.jkbuild"
                artifact = "%s"
                version  = "%s"
                """.formatted(artifact, version);
    }

    @Test
    void add_path_adds_dep_edge_and_registers_member(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "dev.jkbuild"
                artifact = "jk"
                version  = "0.1.0"

                [workspace]
                members = ["app"]
                """);
        write(tmp.resolve("app/jk.toml"), member("app", "0.1.0"));
        write(tmp.resolve("libb/jk.toml"), member("libb", "0.2.0"));

        int exit = Jk.execute("add", "../libb", "-C", tmp.resolve("app").toString());
        assertThat(exit).isEqualTo(0);

        // Pinned dep edge into the current (app) project; artifact omitted since
        // it matches the key.
        String appToml = Files.readString(tmp.resolve("app/jk.toml"));
        assertThat(appToml).contains(
                "libb = { group = \"dev.jkbuild\", version = \"=0.2.0\" }");

        // libb is now registered in the workspace root.
        JkBuild root = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(root.workspace().members()).containsExactly("app", "libb");
    }

    @Test
    void add_colon_prefixed_name_is_a_local_member(@TempDir Path tmp) throws IOException {
        // `:jackson` — explicit local marker, no path separator.
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "dev.jkbuild"
                artifact = "jk"
                version  = "0.1.0"

                [workspace]
                members = ["core"]
                """);
        write(tmp.resolve("jackson/jk.toml"), member("jackson", "1.0.0"));

        int exit = Jk.execute("add", ":jackson", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml")))
                .contains("jackson = { group = \"dev.jkbuild\", version = \"=1.0.0\" }");
        assertThat(JkBuildParser.parse(tmp.resolve("jk.toml")).workspace().members())
                .containsExactly("core", "jackson");
    }

    @Test
    void add_trailing_slash_is_a_local_member(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "dev.jkbuild"
                artifact = "jk"
                version  = "0.1.0"

                [workspace]
                members = ["core"]
                """);
        write(tmp.resolve("jackson/jk.toml"), member("jackson", "2.0.0"));

        int exit = Jk.execute("add", "jackson/", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(JkBuildParser.parse(tmp.resolve("jk.toml")).workspace().members())
                .containsExactly("core", "jackson");
    }

    @Test
    void add_backslash_path_within_workspace_is_a_local_member(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "dev.jkbuild"
                artifact = "jk"
                version  = "0.1.0"

                [workspace]
                members = ["app"]
                """);
        write(tmp.resolve("app/jk.toml"), member("app", "0.1.0"));
        write(tmp.resolve("libb/jk.toml"), member("libb", "0.2.0"));

        // Windows-style separators, resolved relative to the member dir.
        int exit = Jk.execute("add", "..\\libb", "-C", tmp.resolve("app").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("app/jk.toml")))
                .contains("libb = { group = \"dev.jkbuild\", version = \"=0.2.0\" }");
        assertThat(JkBuildParser.parse(tmp.resolve("jk.toml")).workspace().members())
                .containsExactly("app", "libb");
    }

    @Test
    void add_bare_name_is_resolved_as_alias_not_path(@TempDir Path tmp) throws IOException {
        // A bare name with no separators and no leading ':' is an alias, even
        // when a directory by that name exists. Unknown alias + no --group →
        // usage error, and the workspace is left untouched.
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "dev.jkbuild"
                artifact = "jk"
                version  = "0.1.0"

                [workspace]
                members = []
                """);
        write(tmp.resolve("jackson/jk.toml"), member("jackson", "1.0.0"));

        int exit = Jk.execute("add", "jackson", "-C", tmp.toString());
        assertThat(exit).isEqualTo(64); // EX_USAGE — alias resolution, not a path
        assertThat(JkBuildParser.parse(tmp.resolve("jk.toml")).workspace().members()).isEmpty();
    }

    @Test
    void add_maven_coord_is_unchanged_and_leaves_workspace_alone(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "dev.jkbuild"
                artifact = "jk"
                version  = "0.1.0"

                [workspace]
                members = ["app"]
                """);

        int exit = Jk.execute("add", "com.foo:bar:1.2.3", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);

        String toml = Files.readString(tmp.resolve("jk.toml"));
        assertThat(toml).contains("bar = { group = \"com.foo\", version = \"=1.2.3\" }");
        // Coord add must not touch the members list.
        JkBuild root = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(root.workspace().members()).containsExactly("app");
    }
}
