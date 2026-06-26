// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceLocatorTest {

    private static final String ROOT = """
            [project]
            group    = "dev.jkbuild"
            name     = "jk"
            version  = "0.1.0"

            [workspace]
            modules = ["core"]
            """;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void finds_enclosing_workspace_for_unlisted_module(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), ROOT);
        // `app` is NOT in modules yet — findEnclosingWorkspace must still find the root.
        Path app = Files.createDirectories(tmp.resolve("app"));

        assertThat(WorkspaceLocator.findEnclosingWorkspace(app))
                .contains(tmp.toAbsolutePath().normalize());
    }

    @Test
    void finds_enclosing_workspace_for_nested_path(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), ROOT);
        Path nested = Files.createDirectories(tmp.resolve("packages/foo"));

        assertThat(WorkspaceLocator.findEnclosingWorkspace(nested))
                .contains(tmp.toAbsolutePath().normalize());
    }

    @Test
    void workspace_root_is_not_its_own_enclosing_workspace(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), ROOT);
        // Strict-ancestor search: the root dir itself has no enclosing workspace.
        assertThat(WorkspaceLocator.findEnclosingWorkspace(tmp)).isEmpty();
    }

    @Test
    void standalone_project_has_no_enclosing_workspace(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"
                """);
        Path sub = Files.createDirectories(tmp.resolve("sub"));
        assertThat(WorkspaceLocator.findEnclosingWorkspace(sub)).isEmpty();
    }
}
