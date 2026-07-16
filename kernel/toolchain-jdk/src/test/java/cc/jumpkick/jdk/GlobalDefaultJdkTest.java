// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobalDefaultJdkTest {

    @Test
    void set_writes_symlinks_and_config_record(@TempDir Path tempDir) throws IOException {
        Path defaultSymlink = tempDir.resolve("share/jk/default-jdk");
        Path currentSymlink = tempDir.resolve("share/jk/current-jdk");
        Path configFile = tempDir.resolve("config/jk.toml");
        Path jdkHome = Files.createDirectories(tempDir.resolve("jdks/temurin-21.0.5"));

        GlobalDefaultJdk gdj = new GlobalDefaultJdk(defaultSymlink, currentSymlink, configFile);
        gdj.set(new InstalledJdk("temurin-21.0.5", jdkHome));

        assertThat(Files.isSymbolicLink(defaultSymlink)).isTrue();
        assertThat(Files.readSymbolicLink(defaultSymlink)).isEqualTo(jdkHome);
        // current-jdk mirrors default on set().
        assertThat(Files.isSymbolicLink(currentSymlink)).isTrue();
        assertThat(Files.readSymbolicLink(currentSymlink)).isEqualTo(jdkHome);
        assertThat(Files.readString(configFile)).contains("default-jdk = \"temurin-21.0.5\"");
    }

    @Test
    void default_and_default_graal_coexist_independently(@TempDir Path tempDir) throws IOException {
        Path data = tempDir.resolve("share/jk");
        Path configFile = tempDir.resolve("config/jk.toml");
        Path javaHome = Files.createDirectories(tempDir.resolve("jdks/temurin-25.0.3"));
        Path graalHome = Files.createDirectories(tempDir.resolve("jdks/graalvm-25.0.3"));

        GlobalDefaultJdk gdj = new GlobalDefaultJdk(
                data.resolve("default-jdk"),
                data.resolve("current-jdk"),
                data.resolve("default-graal-jdk"),
                configFile);
        gdj.set(new InstalledJdk("temurin-25.0.3", javaHome));
        gdj.setGraal(new InstalledJdk("graalvm-25.0.3", graalHome));

        // Both keys present, independent.
        assertThat(Files.readString(configFile))
                .contains("default-jdk = \"temurin-25.0.3\"")
                .contains("default-graal-jdk = \"graalvm-25.0.3\"");
        assertThat(gdj.currentIdentifier()).contains("temurin-25.0.3");
        assertThat(gdj.graalIdentifier()).contains("graalvm-25.0.3");
        // Home paths are recorded too, so the exact install is unambiguous even
        // when two installs share a vendor-major identifier.
        assertThat(gdj.defaultHome()).contains(javaHome);
        assertThat(gdj.graalHome()).contains(graalHome);

        // Clearing graal leaves the java default intact (and vice-versa).
        gdj.clearGraal();
        assertThat(gdj.graalIdentifier()).isEmpty();
        assertThat(gdj.currentIdentifier()).contains("temurin-25.0.3");
        assertThat(Files.readString(configFile))
                .contains("default-jdk = \"temurin-25.0.3\"")
                .doesNotContain("default-graal-jdk");

        gdj.setGraal(new InstalledJdk("graalvm-25.0.3", graalHome));
        gdj.clear();
        assertThat(gdj.currentIdentifier()).isEmpty();
        assertThat(gdj.graalIdentifier()).contains("graalvm-25.0.3");
    }

    @Test
    void set_replaces_existing_symlinks(@TempDir Path tempDir) throws IOException {
        Path defaultSymlink = tempDir.resolve("share/jk/default-jdk");
        Path currentSymlink = tempDir.resolve("share/jk/current-jdk");
        Path configFile = tempDir.resolve("config/jk.toml");
        Path first = Files.createDirectories(tempDir.resolve("jdks/temurin-21"));
        Path second = Files.createDirectories(tempDir.resolve("jdks/temurin-25"));

        GlobalDefaultJdk gdj = new GlobalDefaultJdk(defaultSymlink, currentSymlink, configFile);
        gdj.set(new InstalledJdk("temurin-21", first));
        gdj.set(new InstalledJdk("temurin-25", second));

        assertThat(Files.readSymbolicLink(defaultSymlink)).isEqualTo(second);
        assertThat(Files.readSymbolicLink(currentSymlink)).isEqualTo(second);
        assertThat(Files.readString(configFile))
                .contains("default-jdk = \"temurin-25\"")
                .doesNotContain("temurin-21");
    }

    @Test
    void set_current_flips_only_current_symlink(@TempDir Path tempDir) throws IOException {
        Path defaultSymlink = tempDir.resolve("share/jk/default-jdk");
        Path currentSymlink = tempDir.resolve("share/jk/current-jdk");
        Path configFile = tempDir.resolve("config/jk.toml");
        Path defaultHome = Files.createDirectories(tempDir.resolve("jdks/temurin-25"));
        Path projectHome = Files.createDirectories(tempDir.resolve("jdks/temurin-21"));

        GlobalDefaultJdk gdj = new GlobalDefaultJdk(defaultSymlink, currentSymlink, configFile);
        gdj.set(new InstalledJdk("temurin-25", defaultHome));
        gdj.setCurrent(new InstalledJdk("temurin-21", projectHome));

        assertThat(Files.readSymbolicLink(defaultSymlink)).isEqualTo(defaultHome);
        assertThat(Files.readSymbolicLink(currentSymlink)).isEqualTo(projectHome);
        // The config record is untouched by setCurrent.
        assertThat(Files.readString(configFile))
                .contains("default-jdk = \"temurin-25\"")
                .doesNotContain("temurin-21");
    }

    @Test
    void set_preserves_other_config_keys(@TempDir Path tempDir) throws IOException {
        Path defaultSymlink = tempDir.resolve("share/jk/default-jdk");
        Path currentSymlink = tempDir.resolve("share/jk/current-jdk");
        Path configFile = tempDir.resolve("config/jk.toml");
        Path jdkHome = Files.createDirectories(tempDir.resolve("jdks/temurin-21"));

        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                # user config
                color = "auto"
                default-jdk = "temurin-17"
                """, StandardCharsets.UTF_8);

        new GlobalDefaultJdk(defaultSymlink, currentSymlink, configFile).set(new InstalledJdk("temurin-21", jdkHome));

        String contents = Files.readString(configFile);
        assertThat(contents).contains("# user config");
        assertThat(contents).contains("color = \"auto\"");
        assertThat(contents).contains("default-jdk = \"temurin-21\"");
        assertThat(contents).doesNotContain("temurin-17");
    }

    @Test
    void clear_removes_symlinks_and_default_jdk_line(@TempDir Path tempDir) throws IOException {
        Path defaultSymlink = tempDir.resolve("share/jk/default-jdk");
        Path currentSymlink = tempDir.resolve("share/jk/current-jdk");
        Path configFile = tempDir.resolve("config/jk.toml");
        Path jdkHome = Files.createDirectories(tempDir.resolve("jdks/temurin-21"));

        GlobalDefaultJdk gdj = new GlobalDefaultJdk(defaultSymlink, currentSymlink, configFile);
        gdj.set(new InstalledJdk("temurin-21", jdkHome));
        assertThat(Files.isSymbolicLink(defaultSymlink)).isTrue();
        assertThat(Files.isSymbolicLink(currentSymlink)).isTrue();

        gdj.clear();

        assertThat(Files.exists(defaultSymlink)).isFalse();
        assertThat(Files.exists(currentSymlink)).isFalse();
        assertThat(Files.readString(configFile)).doesNotContain("default-jdk");
        assertThat(gdj.currentIdentifier()).isEmpty();
    }

    @Test
    void clear_preserves_other_config_keys(@TempDir Path tempDir) throws IOException {
        Path defaultSymlink = tempDir.resolve("share/jk/default-jdk");
        Path currentSymlink = tempDir.resolve("share/jk/current-jdk");
        Path configFile = tempDir.resolve("config/jk.toml");

        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                color = "auto"
                default-jdk = "temurin-21"
                cache-dir = "/var/cache/jk"
                """, StandardCharsets.UTF_8);

        new GlobalDefaultJdk(defaultSymlink, currentSymlink, configFile).clear();

        String contents = Files.readString(configFile);
        assertThat(contents).contains("color = \"auto\"");
        assertThat(contents).contains("cache-dir");
        assertThat(contents).doesNotContain("default-jdk");
    }

    @Test
    void current_identifier_reads_from_config(@TempDir Path tempDir) throws IOException {
        Path defaultSymlink = tempDir.resolve("share/jk/default-jdk");
        Path currentSymlink = tempDir.resolve("share/jk/current-jdk");
        Path configFile = tempDir.resolve("config/jk.toml");

        GlobalDefaultJdk gdj = new GlobalDefaultJdk(defaultSymlink, currentSymlink, configFile);
        assertThat(gdj.currentIdentifier()).isEmpty();

        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, "default-jdk = \"temurin-25.0.1\"\n", StandardCharsets.UTF_8);
        assertThat(gdj.currentIdentifier()).hasValue("temurin-25.0.1");
    }
}
