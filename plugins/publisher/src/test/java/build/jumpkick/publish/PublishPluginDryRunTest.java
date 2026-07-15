// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.publish;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the terminal {@link build.jumpkick.plugin.build.PublishExtension} path end-to-end in
 * dry-run mode (no network): the worker spec is parsed, mapped onto a {@code PublishContext}, and
 * {@link PublishPlugin#publish} assembles the artifacts and reports them — proving the spec→config
 * roundtrip without a live repository.
 */
class PublishPluginDryRunTest {

    @Test
    void dry_run_assembles_artifacts_and_reports_them(@TempDir Path dir) throws Exception {
        // A minimal, parseable project + a stand-in jar.
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "widget"
                version = "1.2.3"
                """);
        Path jar = dir.resolve("widget-1.2.3.jar");
        Files.write(jar, new byte[] {0x50, 0x4b, 0x05, 0x06}); // empty-zip signature

        Path spec = dir.resolve("publish.spec");
        Files.write(spec, List.of(
                "PROJECT_DIR " + dir.toAbsolutePath(),
                "JAR " + jar.toAbsolutePath(),
                "REPO_URL https://repo.example.com/",
                "REPO_AUTH_TYPE anonymous",
                "DRY_RUN true"));

        var buffer = new ByteArrayOutputStream();
        ProtocolWriter out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKPU:");
        int exit = new PublishPlugin().run(List.of(spec.toString()), out);

        assertThat(exit).isZero();
        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"t\":\"result\"").contains("\"dry_run\":true").contains("\"ok\":true");
        // jar + pom at minimum (sources/slsa/sbom off) → files >= 2.
        assertThat(output).containsPattern("\"files\":[2-9]");
    }
}
