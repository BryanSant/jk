// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.model.JkBuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JkBuildWorkspaceTest {

    private static final String LEAF_PROJECT = """
            [project]
            group    = "com.example"
            name     = "leaf"
            version  = "0.1.0"
            """;

    @Test
    void parses_workspace_members() {
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                members = ["libs/core", "services/api"]
                """);

        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(parsed.workspace().members())
                .containsExactly("libs/core", "services/api");
    }

    @Test
    void absent_workspace_block_yields_null() {
        JkBuild parsed = JkBuildParser.parse(LEAF_PROJECT);
        assertThat(parsed.isWorkspaceRoot()).isFalse();
        assertThat(parsed.workspace()).isNull();
    }

    @Test
    void empty_workspace_block_is_not_a_root() {
        JkBuild parsed = JkBuildParser.parse(LEAF_PROJECT + """
                [workspace]
                """);
        assertThat(parsed.isWorkspaceRoot()).isFalse();
        assertThat(parsed.workspace().members()).isEmpty();
    }

    @Test
    void non_list_members_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(LEAF_PROJECT + """
                [workspace]
                members = "libs/core"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("list");
    }

    @Test
    void workspace_loader_loads_member_jk_tomls(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                members = ["libs/a", "libs/b"]
                """);
        for (String name : new String[]{"libs/a", "libs/b"}) {
            Path memberDir = tempDir.resolve(name);
            Files.createDirectories(memberDir);
            Files.writeString(memberDir.resolve("jk.toml"), """
                    [project]
                    group    = "com.example"
                    name     = "%s"
                    version  = "0.1.0"
                    """.formatted(name.replace('/', '-')));
        }

        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> members = WorkspaceLoader.loadMembers(tempDir, root);
        assertThat(members).hasSize(2);
        assertThat(members).containsKey(tempDir.resolve("libs/a"));
    }

    @Test
    void workspace_loader_reports_missing_member(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                members = ["libs/missing"]
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadMembers(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("missing jk.toml");
    }

    @Test
    void workspace_loader_rejects_artifact_collision_between_members(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                members = ["libs/a", "libs/b"]
                """);
        // Two members both call themselves `widget-0.1.0` — they'd race to
        // write the same jar under <root>/target/.
        for (String name : new String[]{"libs/a", "libs/b"}) {
            Path memberDir = tempDir.resolve(name);
            Files.createDirectories(memberDir);
            Files.writeString(memberDir.resolve("jk.toml"), """
                    [project]
                    group    = "com.example"
                    name     = "widget"
                    version  = "0.1.0"
                    """);
        }
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadMembers(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace artifact collision")
                .hasMessageContaining("widget-0.1.0.jar")
                .hasMessageContaining("libs/a")
                .hasMessageContaining("libs/b");
    }

    @Test
    void workspace_loader_rejects_collision_between_root_and_member(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"

                [workspace]
                members = ["libs/a"]
                """);
        Path memberA = tempDir.resolve("libs/a");
        Files.createDirectories(memberA);
        Files.writeString(memberA.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadMembers(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace artifact collision")
                .hasMessageContaining("<workspace root>")
                .hasMessageContaining("libs/a");
    }

    @Test
    void workspace_loader_rejects_nested_workspaces(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                members = ["libs/a"]
                """);
        Path memberA = tempDir.resolve("libs/a");
        Files.createDirectories(memberA);
        // Member tries to declare its own [workspace] — should be rejected.
        Files.writeString(memberA.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "a"
                version  = "0.1.0"

                [workspace]
                members = ["sub"]
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadMembers(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspaces cannot be nested")
                .hasMessageContaining("libs/a");
    }

    @Test
    void workspace_loader_allows_same_artifact_with_different_versions(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                members = ["libs/a", "libs/b"]
                """);
        // Same artifact, different versions → no collision (jar filenames differ).
        Path a = tempDir.resolve("libs/a");
        Files.createDirectories(a);
        Files.writeString(a.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"
                """);
        Path b = tempDir.resolve("libs/b");
        Files.createDirectories(b);
        Files.writeString(b.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.2.0"
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> members = WorkspaceLoader.loadMembers(tempDir, root);
        assertThat(members).hasSize(2);
    }
}
