// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.layout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildLayoutTest {

    /** Library project: no {@code project.main}. Artifacts land in {@code target/lib/}. */
    private static JkBuild project(String artifact, String version) {
        return JkBuild.of(new JkBuild.Project("com.acme", artifact, version, 25));
    }

    /** Application project: has {@code [application].main}. Artifacts land in {@code target/}. */
    private static JkBuild appProject(String artifact, String version) {
        return dev.jkbuild.config.JkBuildParser.parse("""
                [project]
                group   = "com.acme"
                name    = "%s"
                version = "%s"
                java    = 25

                [application]
                main    = "com.acme.Main"
                """.formatted(artifact, version));
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

        assertThat(layout.classesDir()).isEqualTo(dir.resolve("target/classes/main"));
        assertThat(layout.testClassesDir()).isEqualTo(dir.resolve("target/classes/test"));
        assertThat(layout.kotlinClassesDir()).isEqualTo(dir.resolve("target/kotlin/main"));
        assertThat(layout.kotlinTestClassesDir()).isEqualTo(dir.resolve("target/kotlin/test"));
        assertThat(layout.resourcesDir()).isEqualTo(dir.resolve("target/resources/main"));
        assertThat(layout.testResourcesDir()).isEqualTo(dir.resolve("target/resources/test"));
        assertThat(layout.tmpDir()).isEqualTo(dir.resolve("target/tmp"));
        assertThat(layout.generatedSourcesDir("immutables"))
                .isEqualTo(dir.resolve("target/generated/sources/immutables/main"));
        assertThat(layout.testResultsDir()).isEqualTo(dir.resolve("target/reports/test-results"));
        assertThat(layout.markdownTestResults()).isEqualTo(dir.resolve("target/reports/test-results.md"));
    }

    @Test
    void library_artifacts_live_under_target_lib(@TempDir Path dir) {
        // No project.main → library; all deliverables go to target/lib/
        BuildLayout layout = BuildLayout.of(dir, project("widget", "1.2.3"));

        assertThat(layout.artifactDir()).isEqualTo(dir.resolve("target/lib"));
        assertThat(layout.mainJar()).isEqualTo(dir.resolve("target/lib/widget-1.2.3.jar"));
        assertThat(layout.sourcesJar()).isEqualTo(dir.resolve("target/lib/widget-1.2.3-sources.jar"));
        assertThat(layout.javadocJar()).isEqualTo(dir.resolve("target/lib/widget-1.2.3-javadoc.jar"));
        assertThat(layout.nativeBinary()).isEqualTo(dir.resolve("target/lib/widget"));
        // Shared-library base: lib<artifact> in target/lib/, no extension (native-image adds it).
        assertThat(layout.nativeLibrary()).isEqualTo(dir.resolve("target/lib/libwidget"));
        assertThat(layout.ociImageTar()).isEqualTo(dir.resolve("target/lib/widget.oci.tar"));
        assertThat(layout.testReportsDir("core")).isEqualTo(dir.resolve("target/reports/core"));
        assertThat(layout.sbomDir()).isEqualTo(dir.resolve("target/lib/widget-1.2.3-sbom"));
        assertThat(layout.provenanceDir()).isEqualTo(dir.resolve("target/lib/widget-1.2.3-provenance"));
    }

    @Test
    void application_artifacts_live_under_target(@TempDir Path dir) {
        // [application].main declared → application; all deliverables go to target/
        BuildLayout layout = BuildLayout.of(dir, appProject("widget", "1.2.3"));

        assertThat(layout.artifactDir()).isEqualTo(dir.resolve("target"));
        assertThat(layout.mainJar()).isEqualTo(dir.resolve("target/widget-1.2.3.jar"));
        assertThat(layout.sourcesJar()).isEqualTo(dir.resolve("target/widget-1.2.3-sources.jar"));
        assertThat(layout.nativeBinary()).isEqualTo(dir.resolve("target/widget"));
        assertThat(layout.ociImageTar()).isEqualTo(dir.resolve("target/widget.oci.tar"));
    }

    @Test
    void workspace_root_can_differ_from_module_root(@TempDir Path workspace) {
        Path module = workspace.resolve("core");
        JkBuild proj = project("jk-core", "0.7.0");
        BuildLayout layout = BuildLayout.of(workspace, module, proj);

        // Intermediates stay with the module (under module/target/).
        assertThat(layout.classesDir()).isEqualTo(module.resolve("target/classes/main"));
        // No main → library; artifacts under module/target/lib/.
        assertThat(layout.mainJar()).isEqualTo(module.resolve("target/lib/jk-core-0.7.0.jar"));
        assertThat(layout.nativeBinary()).isEqualTo(module.resolve("target/lib/jk-core"));
    }

    @Test
    void of_auto_discovers_enclosing_workspace_root(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "ws-root"
                version  = "1.0.0"

                [workspace]
                modules = ["core"]
                """);
        Path module = workspace.resolve("core");
        Files.createDirectories(module);
        Files.writeString(module.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "core"
                version  = "1.0.0"
                """);

        JkBuild moduleProject = dev.jkbuild.config.JkBuildParser.parse(module.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(module, moduleProject);

        assertThat(layout.workspaceRoot()).isEqualTo(workspace.toAbsolutePath().normalize());
        assertThat(layout.moduleRoot()).isEqualTo(module);
        // Each module owns its own target/ — no main → library, jar in target/lib/.
        assertThat(layout.mainJar()).isEqualTo(module.resolve("target/lib/core-1.0.0.jar"));
        // Intermediates stay module-local (module/target/).
        assertThat(layout.classesDir()).isEqualTo(module.resolve("target/classes/main"));
    }

    @Test
    void of_workspace_root_itself_keeps_target_at_root(@TempDir Path workspace) {
        BuildLayout layout = BuildLayout.of(workspace, workspaceRootProject("ws-root", "1.0.0"));
        assertThat(layout.workspaceRoot()).isEqualTo(workspace);
        assertThat(layout.moduleRoot()).isEqualTo(workspace);
        // No main → library; jar under target/lib/.
        assertThat(layout.mainJar()).isEqualTo(workspace.resolve("target/lib/ws-root-1.0.0.jar"));
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
    void hasMain_false_for_library(@TempDir Path dir) {
        assertThat(BuildLayout.of(dir, project("widget", "1.0.0")).hasMain()).isFalse();
    }

    @Test
    void hasMain_true_for_application(@TempDir Path dir) {
        assertThat(BuildLayout.of(dir, appProject("widget", "1.0.0")).hasMain()).isTrue();
    }

    @Test
    void artifact_and_version_are_exposed(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("alpha", "9.9.9-SNAPSHOT"));

        assertThat(layout.artifact()).isEqualTo("alpha");
        assertThat(layout.version()).isEqualTo("9.9.9-SNAPSHOT");
    }
}
