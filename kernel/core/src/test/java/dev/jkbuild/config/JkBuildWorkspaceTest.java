// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkbuild.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkBuildWorkspaceTest {

    private static final String LEAF_PROJECT = """
            [project]
            group    = "com.example"
            name     = "leaf"
            version  = "0.1.0"
            """;

    @Test
    void parses_workspace_modules() {
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/core", "services/api"]
                """);

        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(parsed.workspace().modules()).containsExactly("libs/core", "services/api");
    }

    @Test
    void members_is_an_undocumented_synonym_for_modules() {
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "root"
                version = "0.1.0"

                [workspace]
                members = ["libs/core", "services/api"]
                """);

        assertThat(parsed.workspace().modules()).containsExactly("libs/core", "services/api");
    }

    @Test
    void modules_and_members_merge_with_members_appended() {
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "root"
                version = "0.1.0"

                [workspace]
                modules = ["a", "b"]
                members = ["c", "d"]
                """);

        // modules first, then members appended — in declaration order within each.
        assertThat(parsed.workspace().modules()).containsExactly("a", "b", "c", "d");
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
        assertThat(parsed.workspace().modules()).isEmpty();
    }

    @Test
    void non_list_modules_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(LEAF_PROJECT + """
                [workspace]
                modules = "libs/core"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("list");
    }

    @Test
    void workspace_loader_loads_module_jk_tomls(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/a", "libs/b"]
                """);
        for (String name : new String[] {"libs/a", "libs/b"}) {
            Path moduleDir = tempDir.resolve(name);
            Files.createDirectories(moduleDir);
            Files.writeString(moduleDir.resolve("jk.toml"), """
                    [project]
                    group    = "com.example"
                    name     = "%s"
                    version  = "0.1.0"
                    """.formatted(name.replace('/', '-')));
        }

        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules).hasSize(2);
        assertThat(modules).containsKey(tempDir.resolve("libs/a"));
    }

    @Test
    void workspace_loader_reports_missing_module(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/missing"]
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("missing jk.toml");
    }

    @Test
    void workspace_loader_rejects_artifact_collision_between_modules(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "root"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/a", "libs/b"]
                """);
        // Two modules both call themselves `widget-0.1.0` — they'd race to
        // write the same jar under <root>/target/.
        for (String name : new String[] {"libs/a", "libs/b"}) {
            Path moduleDir = tempDir.resolve(name);
            Files.createDirectories(moduleDir);
            Files.writeString(moduleDir.resolve("jk.toml"), """
                    [project]
                    group    = "com.example"
                    name     = "widget"
                    version  = "0.1.0"
                    """);
        }
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace artifact collision")
                .hasMessageContaining("widget-0.1.0.jar")
                .hasMessageContaining("libs/a")
                .hasMessageContaining("libs/b");
    }

    @Test
    void workspace_loader_rejects_collision_between_root_and_module(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"

                [workspace]
                modules = ["libs/a"]
                """);
        Path moduleA = tempDir.resolve("libs/a");
        Files.createDirectories(moduleA);
        Files.writeString(moduleA.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
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
                modules = ["libs/a"]
                """);
        Path moduleA = tempDir.resolve("libs/a");
        Files.createDirectories(moduleA);
        // Module tries to declare its own [workspace] — should be rejected.
        Files.writeString(moduleA.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "a"
                version  = "0.1.0"

                [workspace]
                modules = ["sub"]
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(tempDir, root))
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
                modules = ["libs/a", "libs/b"]
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
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(tempDir, root);
        assertThat(modules).hasSize(2);
    }
}
