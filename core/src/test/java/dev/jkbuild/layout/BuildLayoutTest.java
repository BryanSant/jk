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
        assertThat(layout.memberRoot()).isEqualTo(dir);
        assertThat(layout.buildDir()).isEqualTo(dir.resolve("target/build"));
        assertThat(layout.targetDir()).isEqualTo(dir.resolve("target"));
    }

    @Test
    void intermediates_live_under_target_build(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "0.1.0"));

        assertThat(layout.classesDir())
                .isEqualTo(dir.resolve("target/build/classes/main"));
        assertThat(layout.testClassesDir())
                .isEqualTo(dir.resolve("target/build/classes/test"));
        assertThat(layout.kotlinClassesDir())
                .isEqualTo(dir.resolve("target/build/kotlin/main"));
        assertThat(layout.kotlinTestClassesDir())
                .isEqualTo(dir.resolve("target/build/kotlin/test"));
        assertThat(layout.resourcesDir())
                .isEqualTo(dir.resolve("target/build/resources/main"));
        assertThat(layout.testResourcesDir())
                .isEqualTo(dir.resolve("target/build/resources/test"));
        assertThat(layout.tmpDir())
                .isEqualTo(dir.resolve("target/build/tmp"));
        assertThat(layout.generatedSourcesDir("immutables"))
                .isEqualTo(dir.resolve("target/build/generated/sources/immutables/main"));
        assertThat(layout.testResultsDir())
                .isEqualTo(dir.resolve("target/build/test-results"));
    }

    @Test
    void final_artifacts_live_under_target(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "1.2.3"));

        assertThat(layout.mainJar()).isEqualTo(dir.resolve("target/widget-1.2.3.jar"));
        assertThat(layout.sourcesJar()).isEqualTo(dir.resolve("target/widget-1.2.3-sources.jar"));
        assertThat(layout.javadocJar()).isEqualTo(dir.resolve("target/widget-1.2.3-javadoc.jar"));
        assertThat(layout.nativeBinary()).isEqualTo(dir.resolve("target/widget"));
        assertThat(layout.ociImageTar()).isEqualTo(dir.resolve("target/widget.oci.tar"));
        assertThat(layout.testReportsDir("core"))
                .isEqualTo(dir.resolve("target/build/reports/core"));
        assertThat(layout.sbomDir())
                .isEqualTo(dir.resolve("target/widget-1.2.3-sbom"));
        assertThat(layout.provenanceDir())
                .isEqualTo(dir.resolve("target/widget-1.2.3-provenance"));
    }

    @Test
    void workspace_root_can_differ_from_member_root(@TempDir Path workspace) {
        Path member = workspace.resolve("core");
        JkBuild proj = project("jk-core", "0.7.0");
        BuildLayout layout = BuildLayout.of(workspace, member, proj);

        // Intermediates stay with the member (under member/target/build/).
        assertThat(layout.classesDir())
                .isEqualTo(member.resolve("target/build/classes/main"));
        // Final artifacts land at the workspace root.
        assertThat(layout.mainJar()).isEqualTo(workspace.resolve("target/jk-core-0.7.0.jar"));
        assertThat(layout.nativeBinary()).isEqualTo(workspace.resolve("target/jk-core"));
    }

    @Test
    void of_auto_discovers_enclosing_workspace_root(@TempDir Path workspace) throws java.io.IOException {
        java.nio.file.Files.writeString(workspace.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "ws-root"
                version  = "1.0.0"

                [workspace]
                members = ["core"]
                """);
        Path member = workspace.resolve("core");
        java.nio.file.Files.createDirectories(member);
        java.nio.file.Files.writeString(member.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "core"
                version  = "1.0.0"
                """);

        JkBuild memberProject = dev.jkbuild.config.JkBuildParser.parse(member.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(member, memberProject);

        assertThat(layout.workspaceRoot()).isEqualTo(workspace.toAbsolutePath().normalize());
        assertThat(layout.memberRoot()).isEqualTo(member);
        // The jar belongs at the workspace root, NOT the member.
        assertThat(layout.mainJar())
                .isEqualTo(workspace.toAbsolutePath().normalize().resolve("target/core-1.0.0.jar"));
        // But intermediates stay member-local (member/target/build/).
        assertThat(layout.classesDir())
                .isEqualTo(member.resolve("target/build/classes/main"));
    }

    @Test
    void of_workspace_root_itself_keeps_target_at_root(@TempDir Path workspace) {
        BuildLayout layout = BuildLayout.of(workspace,
                workspaceRootProject("ws-root", "1.0.0"));
        assertThat(layout.workspaceRoot()).isEqualTo(workspace);
        assertThat(layout.memberRoot()).isEqualTo(workspace);
        assertThat(layout.mainJar()).isEqualTo(workspace.resolve("target/ws-root-1.0.0.jar"));
    }

    private static JkBuild workspaceRootProject(String artifact, String version) {
        return dev.jkbuild.config.JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "%s"
                version  = "%s"

                [workspace]
                members = []
                """.formatted(artifact, version));
    }

    @Test
    void artifact_and_version_are_exposed(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("alpha", "9.9.9-SNAPSHOT"));

        assertThat(layout.artifact()).isEqualTo("alpha");
        assertThat(layout.version()).isEqualTo("9.9.9-SNAPSHOT");
    }
}
