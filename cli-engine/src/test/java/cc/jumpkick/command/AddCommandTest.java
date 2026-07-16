// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddCommandTest {

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String module(String artifact, String version) {
        return """
                [project]
                group    = "cc.jumpkick"
                name     = "%s"
                version  = "%s"
                """.formatted(artifact, version);
    }

    @Test
    void add_path_adds_dep_edge_and_registers_module(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["app"]
                """);
        write(tmp.resolve("app/jk.toml"), module("app", "0.1.0"));
        write(tmp.resolve("libb/jk.toml"), module("libb", "0.2.0"));

        int exit = Jk.execute("add", "../libb", "-C", tmp.resolve("app").toString());
        assertThat(exit).isEqualTo(0);

        // Pinned dep edge into the current (app) project; artifact omitted since
        // it matches the key.
        String appToml = Files.readString(tmp.resolve("app/jk.toml"));
        assertThat(appToml).contains("libb = { group = \"cc.jumpkick\", version = \"=0.2.0\" }");

        // libb is now registered in the workspace root.
        JkBuild root = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(root.workspace().modules()).containsExactly("app", "libb");
    }

    @Test
    void add_colon_prefixed_name_is_a_local_module(@TempDir Path tmp) throws IOException {
        // `:jackson` — explicit local marker, no path separator.
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["core"]
                """);
        write(tmp.resolve("jackson/jk.toml"), module("jackson", "1.0.0"));

        int exit = Jk.execute("add", ":jackson", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml")))
                .contains("jackson = { group = \"cc.jumpkick\", version = \"=1.0.0\" }");
        assertThat(JkBuildParser.parse(tmp.resolve("jk.toml")).workspace().modules())
                .containsExactly("core", "jackson");
    }

    @Test
    void add_trailing_slash_is_a_local_module(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["core"]
                """);
        write(tmp.resolve("jackson/jk.toml"), module("jackson", "2.0.0"));

        int exit = Jk.execute("add", "jackson/", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(JkBuildParser.parse(tmp.resolve("jk.toml")).workspace().modules())
                .containsExactly("core", "jackson");
    }

    @Test
    void add_backslash_path_within_workspace_is_a_local_module(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["app"]
                """);
        write(tmp.resolve("app/jk.toml"), module("app", "0.1.0"));
        write(tmp.resolve("libb/jk.toml"), module("libb", "0.2.0"));

        // Windows-style separators, resolved relative to the module dir.
        int exit = Jk.execute("add", "..\\libb", "-C", tmp.resolve("app").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("app/jk.toml")))
                .contains("libb = { group = \"cc.jumpkick\", version = \"=0.2.0\" }");
        assertThat(JkBuildParser.parse(tmp.resolve("jk.toml")).workspace().modules())
                .containsExactly("app", "libb");
    }

    @Test
    void add_bare_name_is_resolved_as_library_not_path(@TempDir Path tmp) throws IOException {
        // A bare name with no separators and no leading ':' is a library, even
        // when a directory by that name exists. Unknown library + no --group →
        // usage error, and the workspace is left untouched.
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """);
        write(tmp.resolve("jackson/jk.toml"), module("jackson", "1.0.0"));

        int exit = Jk.execute("add", "jackson", "-C", tmp.toString());
        assertThat(exit).isEqualTo(64); // EX_USAGE — library resolution, not a path
        assertThat(JkBuildParser.parse(tmp.resolve("jk.toml")).workspace().modules())
                .isEmpty();
    }

    @Test
    void add_bare_catalog_name_without_ver_defaults_to_latest(@TempDir Path tmp) throws IOException {
        // A bare name that IS in the library catalog resolves group + artifact
        // and defaults to floating "latest" when --ver is omitted, matching the
        // group:artifact coord form. Resolution happens later at `jk lock`.
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """);

        int exit = Jk.execute("add", "jackson3-core", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);

        // Because jackson3-core is a known catalog library, the editor renders
        // the short form (library name = version) rather than the expanded coord.
        String toml = Files.readString(tmp.resolve("jk.toml"));
        assertThat(toml).contains("jackson3-core = \"latest\"");
    }

    @Test
    void add_bare_catalog_name_with_at_version_pins_floating(@TempDir Path tmp) throws IOException {
        // `library@version` resolves the library and uses the @version as a
        // caret-floating selector, matching group:artifact@version.
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """);

        int exit = Jk.execute("add", "jackson3-core@3.1.0", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("jackson3-core = \"3.1.0\"");
    }

    @Test
    void add_bare_catalog_name_with_at_latest_is_latest(@TempDir Path tmp) throws IOException {
        // `library@latest` is equivalent to omitting the version.
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """);

        int exit = Jk.execute("add", "jackson3-core@latest", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("jackson3-core = \"latest\"");
    }

    @Test
    void add_maven_coord_is_unchanged_and_leaves_workspace_alone(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["app"]
                """);

        int exit = Jk.execute("add", "com.foo:bar:1.2.3", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);

        String toml = Files.readString(tmp.resolve("jk.toml"));
        assertThat(toml).contains("bar = { group = \"com.foo\", version = \"=1.2.3\" }");
        // Coord add must not touch the modules list.
        JkBuild root = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(root.workspace().modules()).containsExactly("app");
    }
}
