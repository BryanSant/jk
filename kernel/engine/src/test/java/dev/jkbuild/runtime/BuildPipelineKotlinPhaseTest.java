// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.run.Goal;
import dev.jkbuild.run.Phase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The build goal composes only the language steps a project opts into. Java and Kotlin are
 * independent opt-ins (given a required {@code jdk}): {@code jdk}+{@code kotlin} ⇒ Kotlin only;
 * {@code jdk}+{@code java}+{@code kotlin} ⇒ both; {@code jdk} alone ⇒ Java only.
 */
class BuildPipelineKotlinPhaseTest {

    @Test
    void jdk_alone_is_a_java_project(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"j\"\nversion=\"0.1.0\"\njdk=25\n");
        assertThat(phaseNames(dir)).contains("compile-java").doesNotContain("compile-kotlin");
    }

    @Test
    void explicit_java_only(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"j\"\nversion=\"0.1.0\"\njava=21\n");
        assertThat(phaseNames(dir)).contains("compile-java").doesNotContain("compile-kotlin");
    }

    @Test
    void jdk_plus_kotlin_is_kotlin_only(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"k\"\nversion=\"0.1.0\"\njdk=25\nkotlin=\"2.3.21\"\n");
        assertThat(phaseNames(dir))
                .contains("compile-kotlin", "write-stamp-kotlin")
                // no Java ⇒ no javac phase and no assembler (Kotlin publishes directly)
                .doesNotContain("compile-java", "write-stamp", "assemble-classes");
    }

    @Test
    void jdk_plus_java_plus_kotlin_enables_both(@TempDir Path dir) throws Exception {
        writeManifest(
                dir, "group=\"com.example\"\nname=\"b\"\nversion=\"0.1.0\"\njdk=25\njava=25\nkotlin=\"2.3.21\"\n");
        assertThat(phaseNames(dir))
                .contains(
                        "compile-java",
                        "compile-kotlin",
                        "write-stamp",
                        "write-stamp-kotlin",
                        // mixed modules add the assembler that merges both outputs
                        "assemble-classes");
    }

    // ---- source detection when neither java nor kotlin is declared ----------

    @Test
    void detects_kotlin_from_src_main_kotlin(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"k\"\nversion=\"0.1.0\"\njdk=25\n");
        Files.createDirectories(dir.resolve("src/main/kotlin"));
        assertThat(phaseNames(dir)).contains("compile-kotlin").doesNotContain("compile-java");
    }

    @Test
    void detects_java_from_src_main_java(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"j\"\nversion=\"0.1.0\"\njdk=25\n");
        Files.createDirectories(dir.resolve("src/main/java"));
        assertThat(phaseNames(dir)).contains("compile-java").doesNotContain("compile-kotlin");
    }

    @Test
    void detects_both_from_a_stray_kt_and_java_file(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"b\"\nversion=\"0.1.0\"\njdk=25\n");
        Path kt = dir.resolve("src/app/Foo.kt");
        Files.createDirectories(kt.getParent());
        Files.writeString(kt, "class Foo");
        Files.writeString(dir.resolve("src/app/Bar.java"), "class Bar {}");
        assertThat(phaseNames(dir)).contains("compile-java", "compile-kotlin");
    }

    @Test
    void explicit_java_ignores_kotlin_sources(@TempDir Path dir) throws Exception {
        // Explicit opt-in wins — stray .kt is not auto-detected when java is declared.
        writeManifest(dir, "group=\"com.example\"\nname=\"j\"\nversion=\"0.1.0\"\njava=21\n");
        Files.createDirectories(dir.resolve("src/main/kotlin"));
        assertThat(phaseNames(dir)).contains("compile-java").doesNotContain("compile-kotlin");
    }

    @Test
    void empty_project_defaults_to_java(@TempDir Path dir) throws Exception {
        writeManifest(dir, "group=\"com.example\"\nname=\"e\"\nversion=\"0.1.0\"\njdk=25\n");
        assertThat(phaseNames(dir)).contains("compile-java").doesNotContain("compile-kotlin");
    }

    private static void writeManifest(Path dir, String projectBody) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "[project]\n" + projectBody);
    }

    private static List<String> phaseNames(Path dir) {
        BuildPipeline.Inputs in = new BuildPipeline.Inputs(
                dir,
                dir.resolve("cache"),
                dir.resolve("jk.toml"),
                dir.resolve("jk.lock"),
                dir,
                1,
                0,
                null,
                null,
                true,
                false);
        Goal goal = BuildPipeline.coreBuilder(in).build();
        return goal.phases().stream().map(Phase::name).toList();
    }
}
