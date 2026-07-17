// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.WorkspaceMerge;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sanity-checks the workspace's {@code jk.toml} files. Failing here means the manifests have
 * drifted from what the parser accepts — fix the manifest (or the parser) before flipping the
 * build.
 */
class SelfHostingTomlTest {

    /**
     * Walk up from the test class's own location until we find a {@code jk.toml} whose {@code
     * [workspace]} table claims this directory as a module. That root is the repo. This is more
     * robust than relying on the JVM's cwd, which differs between the Gradle test launcher
     * (per-module cwd) and a forked test JVM under {@code jk test} (typically inherits the parent
     * process's cwd).
     */
    private static final Path REPO = findRepoRoot();

    private static Path findRepoRoot() {
        // The .class file path tells us where we are on disk regardless of
        // cwd. From there, walk up looking for jk.toml with [workspace].
        try {
            Path classPath = Path.of(SelfHostingTomlTest.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path candidate = classPath.toAbsolutePath().normalize();
            for (int i = 0; i < 12 && candidate != null; i++) {
                Path manifest = candidate.resolve("jk.toml");
                if (java.nio.file.Files.isRegularFile(manifest)) {
                    try {
                        JkBuild parsed = JkBuildParser.parse(manifest);
                        if (parsed.isWorkspaceRoot()) return candidate;
                    } catch (RuntimeException ignored) {
                        // unparseable jk.toml — keep walking
                    }
                }
                candidate = candidate.getParent();
            }
        } catch (Exception ignored) {
            // fall through
        }
        // Last-resort fallback: cwd-relative, two levels up (legacy Gradle
        // test launcher convention).
        return Path.of(".").toAbsolutePath().getParent().getParent();
    }

    @Test
    void root_jk_toml_declares_the_workspace() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        assertThat(root.project().group()).isEqualTo("cc.jumpkick");
        assertThat(root.project().name()).isEqualTo("jk");
        assertThat(root.isWorkspaceRoot()).isTrue();
        assertThat(root.workspace().modules())
                .containsExactly(
                        "shared/jk-api",
                        "shared/plugin-sdk",
                        "shared/core",
                        "shared/client-io",
                        "server/io",
                        "server/resolver",
                        "shared/toolchain-jdk",
                        "server/toolchain",
                        "shared/wire",
                        "server/engine",
                        "clients/cli",
                        "clients/cli-engine");
    }

    @Test
    void every_workspace_module_has_a_parseable_jk_toml() throws Exception {
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        for (String module : root.workspace().modules()) {
            Path moduleManifest = REPO.resolve(module).resolve("jk.toml");
            assertThat(moduleManifest).as("missing " + moduleManifest).exists();
            JkBuild parsed = JkBuildParser.parse(moduleManifest);
            assertThat(parsed.project().group()).isEqualTo("cc.jumpkick");
            assertThat(parsed.project().name()).startsWith("jk-");
            assertThat(parsed.project().jdk()).isEqualTo("25");
        }
    }

    @Test
    void cli_module_declares_a_main_class_and_image_main_class() throws Exception {
        JkBuild cli = JkBuildParser.parse(REPO.resolve("clients/cli/jk.toml"));
        assertThat(cli.mainClass()).isEqualTo("cc.jumpkick.cli.Jk");
        assertThat(cli.isRunnable()).isTrue();

        // The project's jk.toml files declare sibling coordinates
        // explicitly (no `.workspace = true` shorthand) so module builds
        // can resolve lock-time + classpath without needing the parser
        // to apply WorkspaceMerge.
        List<String> mainModules =
                cli.dependencies().of(Scope.MAIN).stream().map(d -> d.module()).toList();
        // The slim client (Stage 5): the wire contract, never the engine itself.
        assertThat(mainModules).contains("cc.jumpkick:jk-core", "cc.jumpkick:jk-engine-api");
        assertThat(mainModules)
                .doesNotContain(
                        "cc.jumpkick:jk-engine",
                        "cc.jumpkick:jk-io",
                        "cc.jumpkick:jk-resolver",
                        "cc.jumpkick:jk-toolchain");

        // The engine application is the one module that links both halves.
        JkBuild cliEngine = JkBuildParser.parse(REPO.resolve("clients/cli-engine/jk.toml"));
        List<String> cliEngineMain = cliEngine.dependencies().of(Scope.MAIN).stream()
                .map(d -> d.module())
                .toList();
        assertThat(cliEngineMain).contains("cc.jumpkick:jk-cli", "cc.jumpkick:jk-engine");

        // Confirm the workspace-root merge still rewrites/dedupes the
        // module coords cleanly when invoked from the root.
        JkBuild root = JkBuildParser.parse(REPO.resolve("jk.toml"));
        JkBuild merged = WorkspaceMerge.merge(
                root, WorkspaceLoader.loadModules(REPO, root).values());
        List<String> mergedRootMain = merged.dependencies().of(Scope.MAIN).stream()
                .map(d -> d.module())
                .toList();
        // jk-engine is a workspace-internal dep and is filtered by WorkspaceMerge;
        // verify an external dep that survives the merge.
        assertThat(mergedRootMain).contains("org.jline:jline-terminal-ffm");
    }
}
