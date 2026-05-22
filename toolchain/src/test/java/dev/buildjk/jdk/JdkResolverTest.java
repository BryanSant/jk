// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JdkResolverTest {

    @Test
    void resolves_from_jk_version_first(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("jdks");
        Files.createDirectories(jdks.resolve("21.0.5-tem-x64-linux"));
        Files.createDirectories(jdks.resolve("23-tem-x64-linux"));

        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".jk-version"), "21.0.5-tem-x64-linux");
        Files.writeString(project.resolve(".sdkmanrc"), "java=23-tem\n");

        JdkResolver resolver = new JdkResolver(new JdkRegistry(jdks));
        assertThat(resolver.resolve(project))
                .isPresent().get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("21.0.5-tem-x64-linux");
    }

    @Test
    void falls_back_to_sdkmanrc(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("jdks");
        Files.createDirectories(jdks.resolve("21.0.5-tem-x64-linux"));

        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".sdkmanrc"),
                "# comment\njava=21.0.5-tem\ngradle=9.5.1\n");

        JdkResolver resolver = new JdkResolver(new JdkRegistry(jdks));
        assertThat(resolver.resolve(project))
                .isPresent().get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("21.0.5-tem-x64-linux");
    }

    @Test
    void empty_when_no_pin(@TempDir Path tempDir) throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        assertThat(new JdkResolver(new JdkRegistry(tempDir.resolve("jdks"))).resolve(project))
                .isEmpty();
    }

    @Test
    void prefix_match_when_exact_identifier_missing(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("jdks");
        Files.createDirectories(jdks.resolve("21.0.5-tem-x64-linux"));
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".jk-version"), "21.0.5-tem");

        assertThat(new JdkResolver(new JdkRegistry(jdks)).resolve(project))
                .isPresent().get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("21.0.5-tem-x64-linux");
    }

    @Test
    void sdkmanrc_parser_skips_comments() {
        String body = """
                # comment
                java=21.0.5-tem
                gradle=9.5.1
                """;
        assertThat(JdkResolver.parseSdkmanrcJavaLine(body)).isEqualTo("21.0.5-tem");
    }
}
