// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.WorkspaceMerge;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity-checks the workspace's {@code jk.toml} files. Failing here means
 * the manifests have drifted from what the parser accepts — fix the
 * manifest (or the parser) before flipping the build.
 */
class SelfHostingTomlTest {

    private static final Path REPO = Path.of(".").toAbsolutePath()
            // The test is in :core; the repo root is two levels up.
            .getParent().getParent();

    @Test
    void root_jk_toml_declares_the_workspace() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        assertThat(root.project().group()).isEqualTo("dev.jkbuild");
        assertThat(root.project().artifact()).isEqualTo("jk");
        assertThat(root.isWorkspaceRoot()).isTrue();
        assertThat(root.workspace().members()).containsExactly(
                "core", "io", "resolver", "toolchain",
                "engine", "supply-chain", "image", "compat", "cli");
    }

    @Test
    void every_workspace_member_has_a_parseable_jk_toml() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        for (String member : root.workspace().members()) {
            Path memberManifest = REPO.resolve(member).resolve("jk.toml");
            assertThat(memberManifest).as("missing " + memberManifest).exists();
            JkBuild parsed = JkBuildParser.parse(memberManifest);
            assertThat(parsed.project().group()).isEqualTo("dev.jkbuild");
            assertThat(parsed.project().artifact()).startsWith("jk-");
            assertThat(parsed.project().jdk()).isEqualTo(25);
        }
    }

    @Test
    void cli_module_declares_a_main_class_and_image_main_class() throws Exception {
        JkBuild cli = JkBuildParser.parse(REPO.resolve("cli/jk.toml"));
        assertThat(cli.project().main()).isEqualTo("dev.jkbuild.cli.Jk");
        assertThat(cli.project().isRunnable()).isTrue();

        // Workspace siblings declared as `name.workspace = true` parse as
        // synthetic `workspace:<name>` placeholders. Verify the raw shape...
        List<String> unmergedModules = cli.dependencies().of(Scope.MAIN).stream()
                .map(d -> d.module()).toList();
        assertThat(unmergedModules).contains(
                "workspace:jk-core",
                "workspace:jk-engine",
                "info.picocli:picocli");

        // ...then merge with the root manifest's siblings to confirm the
        // placeholders rewrite to real coords.
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        JkBuild merged = WorkspaceMerge.merge(
                root, WorkspaceLoader.loadMembers(REPO, root).values());
        List<String> mergedRootMain = merged.dependencies().of(Scope.MAIN).stream()
                .map(d -> d.module()).toList();
        assertThat(mergedRootMain).contains("info.picocli:picocli");
    }
}
