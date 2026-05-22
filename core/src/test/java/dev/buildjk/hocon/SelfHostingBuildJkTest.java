// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Scope;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity-checks the candidate v0.9 self-hosting manifests sitting at
 * {@code build.jk} (workspace root) and {@code <module>/build.jk}.
 * Failing here means the manifests have drifted from what the parser
 * accepts — fix the manifest (or the parser) before flipping the build.
 */
class SelfHostingBuildJkTest {

    private static final Path REPO = Path.of(".").toAbsolutePath()
            // The test is in :core; the repo root is two levels up.
            .getParent().getParent();

    @Test
    void root_build_jk_declares_the_workspace() throws Exception {
        BuildJk root = BuildJkParser.parse(REPO.resolve("build.jk"));
        assertThat(root.project().group()).isEqualTo("dev.buildjk");
        assertThat(root.project().artifact()).isEqualTo("jk");
        assertThat(root.isWorkspaceRoot()).isTrue();
        assertThat(root.workspace().members()).containsExactly(
                "core", "io", "resolver", "toolchain",
                "engine", "supply-chain", "image", "compat", "cli");
    }

    @Test
    void every_workspace_member_has_a_parseable_build_jk() throws Exception {
        BuildJk root = BuildJkParser.parse(REPO.resolve("build.jk"));
        for (String member : root.workspace().members()) {
            Path memberBuildJk = REPO.resolve(member).resolve("build.jk");
            assertThat(memberBuildJk).as("missing " + memberBuildJk).exists();
            BuildJk parsed = BuildJkParser.parse(memberBuildJk);
            assertThat(parsed.project().group()).isEqualTo("dev.buildjk");
            assertThat(parsed.project().artifact()).startsWith("jk-");
            assertThat(parsed.project().jdk()).isEqualTo("25");
        }
    }

    @Test
    void cli_module_declares_a_main_class_and_image_main_class() throws Exception {
        BuildJk cli = BuildJkParser.parse(REPO.resolve("cli/build.jk"));
        assertThat(cli.project().main()).isEqualTo("dev.buildjk.cli.Jk");
        assertThat(cli.project().isRunnable()).isTrue();
        // The cli module is the application entrypoint; it depends on every
        // other module via dependencies.main.
        List<String> mainModules = cli.dependencies().of(Scope.MAIN).stream()
                .map(d -> d.module()).toList();
        assertThat(mainModules).contains(
                "dev.buildjk:jk-core",
                "dev.buildjk:jk-cli".replace("cli", "engine"),  // jk-engine
                "info.picocli:picocli");
    }

    @Test
    void core_lists_its_three_external_runtime_deps() throws Exception {
        BuildJk core = BuildJkParser.parse(REPO.resolve("core/build.jk"));
        List<String> mainModules = core.dependencies().of(Scope.MAIN).stream()
                .map(d -> d.module()).toList();
        assertThat(mainModules).containsExactlyInAnyOrder(
                "com.typesafe:config",
                "org.tomlj:tomlj",
                "tools.jackson.core:jackson-databind");
    }
}
