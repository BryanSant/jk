// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PathDisplay} renders paths relative to the closest of {working dir,
 * workspace root, git root}, and absolute only when outside all three.
 */
class PathDisplayTest {

    @Test
    void relative_to_working_dir_when_under_it(@TempDir Path tmp) {
        Path file = tmp.resolve("src/Main.java");
        assertThat(PathDisplay.of(file, tmp)).isEqualTo("src/Main.java");
    }

    @Test
    void target_equal_to_working_dir_renders_as_dot(@TempDir Path tmp) {
        assertThat(PathDisplay.of(tmp, tmp)).isEqualTo(".");
    }

    @Test
    void sibling_of_working_dir_anchors_on_workspace_root_not_dotdot(@TempDir Path tmp) throws IOException {
        // Workspace root with two members; run from one, reference a file in the other.
        Files.writeString(tmp.resolve("jk.toml"),
                "[project]\ngroup = \"x\"\nname = \"root\"\nversion = \"1\"\n[workspace]\nmembers = [\"app\", \"lib\"]\n");
        Path app = Files.createDirectories(tmp.resolve("app"));
        Path libFile = tmp.resolve("lib/src/Util.java");

        // cwd = app (not an ancestor of libFile) -> anchor on the workspace root.
        assertThat(PathDisplay.of(libFile, app)).isEqualTo("lib/src/Util.java");
    }

    @Test
    void prefers_working_dir_over_workspace_root_when_deeper(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"),
                "[project]\ngroup = \"x\"\nname = \"root\"\nversion = \"1\"\n[workspace]\nmembers = [\"app\"]\n");
        Path app = Files.createDirectories(tmp.resolve("app"));
        Path file = app.resolve("src/Main.java");

        // Both the workspace root and cwd=app contain the file; the deeper anchor (app) wins.
        assertThat(PathDisplay.of(file, app)).isEqualTo("src/Main.java");
    }

    @Test
    void falls_back_to_git_root_when_outside_working_dir(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve(".git"));
        Path cwd = Files.createDirectories(tmp.resolve("a/b"));
        Path file = tmp.resolve("c/d/File.java");

        // Not under cwd and no workspace -> anchor on the git root.
        assertThat(PathDisplay.of(file, cwd)).isEqualTo("c/d/File.java");
    }

    @Test
    void absolute_when_outside_all_scopes(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.createDirectories(project.resolve(".git"));
        Path outside = tmp.resolve("elsewhere/Other.java");

        // No shared anchor with the project -> absolute.
        assertThat(PathDisplay.of(outside, project))
                .isEqualTo(outside.toAbsolutePath().normalize().toString());
    }
}
