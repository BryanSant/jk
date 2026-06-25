// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.layout;

import dev.jkbuild.model.JkBuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BuildLayoutTest {

    private static JkBuild project(String artifact, String version) {
        return JkBuild.of(new JkBuild.Project("com.acme", artifact, version, 25));
    }

    @Test
    void single_project_roots_resolve_to_project_dir(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "0.1.0"));

        assertThat(layout.workspaceRoot()).isEqualTo(dir);
        assertThat(layout.moduleRoot()).isEqualTo(dir);
        assertThat(layout.buildDir()).isEqualTo(dir.resolve("target"));
        assertThat(layout.targetDir()).isEqualTo(dir.resolve("target"));
    }

    @Test
    void intermediates_live_under_target(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "0.1.0"));

        assertThat(layout.classesDir())
                .isEqualTo(dir.resolve("target/classes/main"));
        assertThat(layout.testClassesDir())
                .isEqualTo(dir.resolve("target/classes/test"));
        assertThat(layout.kotlinClassesDir())
                .isEqualTo(dir.resolve("target/kotlin/main"));
        assertThat(layout.kotlinTestClassesDir())
                .isEqualTo(dir.resolve("target/kotlin/test"));
        assertThat(layout.resourcesDir())
                .isEqualTo(dir.resolve("target/resources/main"));
        assertThat(layout.testResourcesDir())
                .isEqualTo(dir.resolve("target/resources/test"));
        assertThat(layout.tmpDir())
                .isEqualTo(dir.resolve("target/tmp"));
        assertThat(layout.generatedSourcesDir("immutables"))
                .isEqualTo(dir.resolve("target/generated/sources/immutables/main"));
        assertThat(layout.testResultsDir())
                .isEqualTo(dir.resolve("target/test-results"));
        assertThat(layout.markdownTestResults())
                .isEqualTo(dir.resolve("target/test-results.md"));
    }

    @Test
    void final_artifacts_live_under_target(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "1.2.3"));

        assertThat(layout.mainJar()).isEqualTo(dir.resolve("target/widget-1.2.3.jar"));
        assertThat(layout.sourcesJar()).isEqualTo(dir.resolve("target/widget-1.2.3-sources.jar"));
        assertThat(layout.javadocJar()).isEqualTo(dir.resolve("target/widget-1.2.3-javadoc.jar"));
        assertThat(layout.nativeBinary()).isEqualTo(dir.resolve("target/widget"));
        // Shared-library base: lib<artifact>, no extension (native-image adds it).
        assertThat(layout.nativeLibrary()).isEqualTo(dir.resolve("target/libwidget"));
        assertThat(layout.ociImageTar()).isEqualTo(dir.resolve("target/widget.oci.tar"));
        assertThat(layout.testReportsDir("core"))
                .isEqualTo(dir.resolve("target/reports/core"));
        assertThat(layout.sbomDir())
                .isEqualTo(dir.resolve("target/widget-1.2.3-sbom"));
        assertThat(layout.provenanceDir())
                .isEqualTo(dir.resolve("target/widget-1.2.3-provenance"));
    }

    @Test
    void workspace_root_can_differ_from_module_root(@TempDir Path workspace) {
        Path module = workspace.resolve("core");
        JkBuild proj = project("jk-core", "0.7.0");
        BuildLayout layout = BuildLayout.of(workspace, module, proj);

        // Intermediates stay with the module (under module/target/).
        assertThat(layout.classesDir())
                .isEqualTo(module.resolve("target/classes/main"));
        // Final artifacts also land under the module's own target/.
        assertThat(layout.mainJar()).isEqualTo(module.resolve("target/jk-core-0.7.0.jar"));
        assertThat(layout.nativeBinary()).isEqualTo(module.resolve("target/jk-core"));
    }

    @Test
    void of_auto_discovers_enclosing_workspace_root(@TempDir Path workspace) throws java.io.IOException {
        java.nio.file.Files.writeString(workspace.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "ws-root"
                version  = "1.0.0"

                [workspace]
                modules = ["core"]
                """);
        Path module = workspace.resolve("core");
        java.nio.file.Files.createDirectories(module);
        java.nio.file.Files.writeString(module.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "core"
                version  = "1.0.0"
                """);

        JkBuild moduleProject = dev.jkbuild.config.JkBuildParser.parse(module.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(module, moduleProject);

        assertThat(layout.workspaceRoot()).isEqualTo(workspace.toAbsolutePath().normalize());
        assertThat(layout.moduleRoot()).isEqualTo(module);
        // Each module owns its own target/ — the jar lives with the module.
        assertThat(layout.mainJar())
                .isEqualTo(module.resolve("target/core-1.0.0.jar"));
        // Intermediates stay module-local (module/target/).
        assertThat(layout.classesDir())
                .isEqualTo(module.resolve("target/classes/main"));
    }

    @Test
    void of_workspace_root_itself_keeps_target_at_root(@TempDir Path workspace) {
        BuildLayout layout = BuildLayout.of(workspace,
                workspaceRootProject("ws-root", "1.0.0"));
        assertThat(layout.workspaceRoot()).isEqualTo(workspace);
        assertThat(layout.moduleRoot()).isEqualTo(workspace);
        assertThat(layout.mainJar()).isEqualTo(workspace.resolve("target/ws-root-1.0.0.jar"));
    }

    private static JkBuild workspaceRootProject(String artifact, String version) {
        return dev.jkbuild.config.JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "%s"
                version  = "%s"

                [workspace]
                modules = []
                """.formatted(artifact, version));
    }

    @Test
    void artifact_and_version_are_exposed(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("alpha", "9.9.9-SNAPSHOT"));

        assertThat(layout.artifact()).isEqualTo("alpha");
        assertThat(layout.version()).isEqualTo("9.9.9-SNAPSHOT");
    }
}
