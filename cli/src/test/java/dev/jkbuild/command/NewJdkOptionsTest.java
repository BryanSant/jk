// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkVendor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewJdkOptionsTest {

    @Test
    void parses_major_from_modern_version() {
        assertThat(NewJdkOptions.parseMajor("25.0.1")).contains(25);
        assertThat(NewJdkOptions.parseMajor("21")).contains(21);
        assertThat(NewJdkOptions.parseMajor("17.0.13+11")).contains(17);
    }

    @Test
    void parses_major_from_legacy_one_dot_eight() {
        assertThat(NewJdkOptions.parseMajor("1.8.0_412")).contains(8);
        assertThat(NewJdkOptions.parseMajor("1.7.0_80")).contains(7);
    }

    @Test
    void parses_major_from_identifier_suffix() {
        assertThat(NewJdkOptions.parseMajorFromIdentifier("temurin-25.0.1")).contains(25);
        assertThat(NewJdkOptions.parseMajorFromIdentifier("corretto-21")).contains(21);
        assertThat(NewJdkOptions.parseMajorFromIdentifier("graalvm-ce-17.0.13")).contains(17);
    }

    @Test
    void registry_only_lists_all_jk_installs(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        makeJdkFixture(jdksRoot.resolve("temurin-25.0.1"), "25.0.1");
        makeJdkFixture(jdksRoot.resolve("corretto-21"), "21");
        var registry = new JdkRegistry(jdksRoot);

        var options = NewJdkOptions.discover(registry, List.of());

        assertThat(options).hasSize(2);
        // Sorted highest-major first.
        assertThat(options.get(0).major()).isEqualTo(25);
        assertThat(options.get(0).id()).isEqualTo("temurin-25.0.1");
        assertThat(options.get(1).major()).isEqualTo(21);
        assertThat(options.get(1).id()).isEqualTo("corretto-21");
        assertThat(options).allMatch(o -> "jk".equals(o.source()));
    }

    @Test
    void discovery_supplements_registry_and_dedupes_by_home(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        makeJdkFixture(jdksRoot.resolve("temurin-25.0.1"), "25.0.1");
        var registry = new JdkRegistry(jdksRoot);
        var managedHome = jdksRoot.resolve("temurin-25.0.1");

        var systemHome = tempDir.resolve("system/jdk-21");
        Files.createDirectories(systemHome.resolve("bin"));

        var hits = List.of(
                // duplicate of the registry entry; must not appear twice
                new JdkHit(managedHome, "25.0.1", JdkVendor.TEMURIN, "JAVA_HOME"),
                new JdkHit(systemHome, "21.0.5", JdkVendor.CORRETTO, "SYSTEM"));

        var options = NewJdkOptions.discover(registry, hits);

        assertThat(options).hasSize(2);
        assertThat(options.get(0).major()).isEqualTo(25); // registry temurin
        assertThat(options.get(0).source()).isEqualTo("jk");
        assertThat(options.get(1).major()).isEqualTo(21); // discovered corretto
        assertThat(options.get(1).source()).isEqualTo("SYSTEM");
    }

    @Test
    void reads_release_file_when_version_missing(@TempDir Path tempDir) throws IOException {
        var home = tempDir.resolve("some-jdk");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("release"), """
                JAVA_VERSION="17.0.13"
                IMPLEMENTOR="Eclipse Adoptium"
                """);
        assertThat(NewJdkOptions.readReleaseMajor(home)).contains(17);
    }

    @Test
    void read_release_returns_empty_when_no_file(@TempDir Path tempDir) {
        assertThat(NewJdkOptions.readReleaseMajor(tempDir.resolve("nope"))).isEqualTo(Optional.empty());
    }

    private static void makeJdkFixture(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
    }
}
