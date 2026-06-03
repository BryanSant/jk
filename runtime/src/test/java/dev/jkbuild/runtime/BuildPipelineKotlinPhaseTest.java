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
 * The build goal composes only the language steps a project opts into. Java and
 * Kotlin are independent opt-ins (given a required {@code jdk}):
 * {@code jdk}+{@code kotlin} ⇒ Kotlin only; {@code jdk}+{@code java}+{@code kotlin}
 * ⇒ both; {@code jdk} alone ⇒ Java only.
 */
class BuildPipelineKotlinPhaseTest {

    @Test
    void jdk_alone_is_a_java_project(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nartifact=\"j\"\nversion=\"0.1.0\"\njdk=25\n");
        assertThat(phaseNames(dir)).contains("compile-java").doesNotContain("compile-kotlin");
    }

    @Test
    void explicit_java_only(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nartifact=\"j\"\nversion=\"0.1.0\"\njava=21\n");
        assertThat(phaseNames(dir)).contains("compile-java").doesNotContain("compile-kotlin");
    }

    @Test
    void jdk_plus_kotlin_is_kotlin_only(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nartifact=\"k\"\nversion=\"0.1.0\"\njdk=25\nkotlin=\"2.3.21\"\n");
        assertThat(phaseNames(dir))
                .contains("compile-kotlin")
                .doesNotContain("compile-java", "write-stamp");
    }

    @Test
    void jdk_plus_java_plus_kotlin_enables_both(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nartifact=\"b\"\nversion=\"0.1.0\"\njdk=25\njava=25\nkotlin=\"2.3.21\"\n");
        assertThat(phaseNames(dir)).contains("compile-java", "compile-kotlin", "write-stamp");
    }

    private static void writeManifest(Path dir, String projectBody) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "[project]\n" + projectBody);
    }

    private static List<String> phaseNames(Path dir) {
        BuildPipeline.Inputs in = new BuildPipeline.Inputs(
                dir, dir.resolve("cache"), dir.resolve("jk.toml"), dir.resolve("jk.lock"), dir,
                1, 0, null, null, true, false);
        Goal goal = BuildPipeline.coreBuilder(in).build();
        return goal.phases().stream().map(Phase::name).toList();
    }
}
