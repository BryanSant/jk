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
        assertThat(layout.buildDir()).isEqualTo(dir.resolve("build"));
        assertThat(layout.targetDir()).isEqualTo(dir.resolve("target"));
    }

    @Test
    void intermediates_live_under_build(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "0.1.0"));

        assertThat(layout.classesDir()).isEqualTo(dir.resolve("build/classes/main"));
        assertThat(layout.testClassesDir()).isEqualTo(dir.resolve("build/classes/test"));
        assertThat(layout.resourcesDir()).isEqualTo(dir.resolve("build/resources/main"));
        assertThat(layout.testResourcesDir()).isEqualTo(dir.resolve("build/resources/test"));
        assertThat(layout.tmpDir()).isEqualTo(dir.resolve("build/tmp"));
        assertThat(layout.generatedSourcesDir("immutables"))
                .isEqualTo(dir.resolve("build/generated/sources/immutables/main"));
    }

    @Test
    void final_artifacts_live_under_target(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "1.2.3"));

        assertThat(layout.mainJar()).isEqualTo(dir.resolve("target/widget-1.2.3.jar"));
        assertThat(layout.sourcesJar()).isEqualTo(dir.resolve("target/widget-1.2.3-sources.jar"));
        assertThat(layout.javadocJar()).isEqualTo(dir.resolve("target/widget-1.2.3-javadoc.jar"));
        assertThat(layout.nativeBinary()).isEqualTo(dir.resolve("target/widget"));
        assertThat(layout.ociImageTar()).isEqualTo(dir.resolve("target/widget.oci.tar"));
        assertThat(layout.testReportsDir("core")).isEqualTo(dir.resolve("target/reports/core"));
        assertThat(layout.sbomDir()).isEqualTo(dir.resolve("target/sbom"));
        assertThat(layout.provenanceDir()).isEqualTo(dir.resolve("target/provenance"));
    }

    @Test
    void workspace_root_can_differ_from_member_root(@TempDir Path workspace) {
        Path member = workspace.resolve("core");
        JkBuild proj = project("jk-core", "0.7.0");
        BuildLayout layout = BuildLayout.of(workspace, member, proj);

        // Intermediates stay with the member.
        assertThat(layout.classesDir()).isEqualTo(member.resolve("build/classes/main"));
        // Final artifacts land at the workspace root.
        assertThat(layout.mainJar()).isEqualTo(workspace.resolve("target/jk-core-0.7.0.jar"));
        assertThat(layout.nativeBinary()).isEqualTo(workspace.resolve("target/jk-core"));
    }

    @Test
    void artifact_and_version_are_exposed(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("alpha", "9.9.9-SNAPSHOT"));

        assertThat(layout.artifact()).isEqualTo("alpha");
        assertThat(layout.version()).isEqualTo("9.9.9-SNAPSHOT");
    }
}
