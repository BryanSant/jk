// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BuildPipeline#siblingMainJars} — the lookup the {@code embed-sha} phase
 * uses to find a {@code [build.embed-sha]} module's output jar. Keyed by both
 * project name and {@code group:artifact} coord.
 */
class EmbedShaResolutionTest {

    private static void writeManifest(Path dir, String toml) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), toml);
    }

    @Test
    void resolves_sibling_jars_by_name_and_coord(@TempDir Path root) throws IOException {
        writeManifest(root, """
                [project]
                group   = "dev.jkbuild"
                name    = "jk"
                version = "1.0.0"

                [workspace]
                modules = ["lib", "host"]
                """);
        writeManifest(root.resolve("lib"), """
                [project]
                group   = "dev.jkbuild"
                name    = "lib"
                version = "1.0.0"
                """);
        writeManifest(root.resolve("host"), """
                [project]
                group   = "dev.jkbuild"
                name    = "host"
                version = "1.0.0"
                """);

        Map<String, Path> jars = BuildPipeline.siblingMainJars(root.resolve("host"));

        Path libDir = root.resolve("lib");
        Path expected = BuildLayout.of(libDir, JkBuildParser.parse(libDir.resolve("jk.toml"))).mainJar();
        assertThat(jars).containsEntry("lib", expected);
        assertThat(jars).containsEntry("dev.jkbuild:lib", expected);
        // The jar need not exist on disk — resolution is path-only.
        assertThat(jars).containsKey("host");
    }

    @Test
    void empty_when_not_in_a_workspace(@TempDir Path dir) throws IOException {
        writeManifest(dir, """
                [project]
                group   = "dev.jkbuild"
                name    = "solo"
                version = "1.0.0"
                """);
        assertThat(BuildPipeline.siblingMainJars(dir)).isEmpty();
    }
}
