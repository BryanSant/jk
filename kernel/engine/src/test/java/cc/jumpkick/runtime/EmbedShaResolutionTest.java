// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BuildPipelines#siblingMainJars} — the lookup the {@code embed-sha} step uses to find a
 * {@code [build.embed-sha]} module's output jar. Keyed by both project name and {@code
 * group:artifact} coord.
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
                group   = "cc.jumpkick"
                name    = "jk"
                version = "1.0.0"

                [workspace]
                modules = ["lib", "host"]
                """);
        writeManifest(root.resolve("lib"), """
                [project]
                group   = "cc.jumpkick"
                name    = "lib"
                version = "1.0.0"
                """);
        writeManifest(root.resolve("host"), """
                [project]
                group   = "cc.jumpkick"
                name    = "host"
                version = "1.0.0"
                """);

        Map<String, Path> jars = BuildPipelines.siblingMainJars(root.resolve("host"));

        Path libDir = root.resolve("lib");
        Path expected = BuildLayout.of(libDir, JkBuildParser.parse(libDir.resolve("jk.toml")))
                .mainJar();
        assertThat(jars).containsEntry("lib", expected);
        assertThat(jars).containsEntry("cc.jumpkick:lib", expected);
        // The jar need not exist on disk — resolution is path-only.
        assertThat(jars).containsKey("host");
    }

    @Test
    void empty_when_not_in_a_workspace(@TempDir Path dir) throws IOException {
        writeManifest(dir, """
                [project]
                group   = "cc.jumpkick"
                name    = "solo"
                version = "1.0.0"
                """);
        assertThat(BuildPipelines.siblingMainJars(dir)).isEmpty();
    }
}
