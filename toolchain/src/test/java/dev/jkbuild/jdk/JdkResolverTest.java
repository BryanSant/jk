// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JdkResolverTest {

    @Test
    void resolves_from_jk_version(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-21.0.5"));
        makeJdkInstall(jdks.resolve("temurin-23"));

        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".jk-version"), "temurin-21.0.5");

        JdkResolver resolver = new JdkResolver(new JdkRegistry(jdks));
        assertThat(resolver.resolve(project))
                .isPresent().get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("temurin-21.0.5");
    }

    @Test
    void prefix_match_when_exact_identifier_missing(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-21.0.5"));
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".jk-version"), "temurin-21");

        assertThat(new JdkResolver(new JdkRegistry(jdks)).resolve(project))
                .isPresent().get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("temurin-21.0.5");
    }

    @Test
    void empty_when_no_pin(@TempDir Path tempDir) throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        assertThat(new JdkResolver(new JdkRegistry(tempDir.resolve("jdks"))).resolve(project))
                .isEmpty();
    }

    /** Realistic install dir: needs a {@code bin/} (and a fake {@code java}) for the registry. */
    private static void makeJdkInstall(Path home) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
    }
}
