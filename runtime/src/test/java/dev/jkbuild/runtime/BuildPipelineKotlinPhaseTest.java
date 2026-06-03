// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.run.Goal;
import dev.jkbuild.run.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code compile-kotlin} phase is composed into the goal only for projects
 * that actually use Kotlin — a Java-only project must not show a "Kotlin" step.
 */
class BuildPipelineKotlinPhaseTest {

    @Test
    void java_only_project_omits_the_kotlin_phase(@TempDir Path dir) throws Exception {
        writeManifest(dir, "[project]\ngroup=\"com.example\"\nartifact=\"j\"\nversion=\"0.1.0\"\njava=21\n");
        assertThat(phaseNames(dir)).doesNotContain("compile-kotlin");
    }

    @Test
    void project_with_declared_kotlin_version_includes_the_kotlin_phase(@TempDir Path dir) throws Exception {
        writeManifest(dir, "[project]\ngroup=\"com.example\"\nartifact=\"k\"\nversion=\"0.1.0\"\nkotlin=\"2.0.0\"\n");
        assertThat(phaseNames(dir)).contains("compile-kotlin");
    }

    @Test
    void java_project_with_kt_sources_still_includes_the_kotlin_phase(@TempDir Path dir) throws Exception {
        writeManifest(dir, "[project]\ngroup=\"com.example\"\nartifact=\"j\"\nversion=\"0.1.0\"\njava=21\n");
        Path kt = dir.resolve("src/main/kotlin/Foo.kt");
        Files.createDirectories(kt.getParent());
        Files.writeString(kt, "class Foo");
        assertThat(phaseNames(dir)).contains("compile-kotlin");
    }

    private static void writeManifest(Path dir, String toml) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), toml);
    }

    private static List<String> phaseNames(Path dir) {
        BuildPipeline.Inputs in = new BuildPipeline.Inputs(
                dir, dir.resolve("cache"), dir.resolve("jk.toml"), dir.resolve("jk.lock"), dir,
                1, 0, null, null, true, false);
        Goal goal = BuildPipeline.coreBuilder(in).build();
        return goal.phases().stream().map(Phase::name).toList();
    }
}
