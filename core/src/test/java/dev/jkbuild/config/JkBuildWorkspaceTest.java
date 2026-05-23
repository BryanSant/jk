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
            artifact = "leaf"
            version  = "0.1.0"
            """;

    @Test
    void parses_workspace_members() {
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group    = "com.example"
                artifact = "root"
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
                artifact = "root"
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
                    artifact = "%s"
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
                artifact = "root"
                version  = "0.1.0"

                [workspace]
                members = ["libs/missing"]
                """);
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadMembers(tempDir, root))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("missing jk.toml");
    }
}
