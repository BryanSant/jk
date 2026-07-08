// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkEnvTest {

    @Test
    void empty_when_no_jk_toml_anywhere(@TempDir Path tempDir) throws IOException {
        var env = new JkEnv(new JdkRegistry(tempDir.resolve("jdks")), "/usr/bin", noGlobalDefault(tempDir));
        var target = env.resolve(tempDir);
        assertThat(target.isActive()).isFalse();
        assertThat(target.projectRoot()).isEmpty();
        assertThat(target.vars()).isEmpty();
    }

    @Test
    void empty_when_jk_toml_exists_but_lock_has_no_jdk(@TempDir Path tempDir) throws IOException {
        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "[project]\ngroup=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(Lockfile.empty("0.1"), project.resolve("jk.lock"));

        var env = new JkEnv(new JdkRegistry(tempDir.resolve("jdks")), "/usr/bin", noGlobalDefault(tempDir));
        assertThat(env.resolve(project).isActive()).isFalse();
    }

    @Test
    void resolves_jdk_home_from_registry(@TempDir Path tempDir) throws IOException {
        // Stand up a fake jk-managed JDK install + a project with jk.lock
        // pointing at it.
        var jdksRoot = tempDir.resolve("jdks");
        var jdkHome = jdksRoot.resolve("temurin-25.0.3");
        Files.createDirectories(jdkHome.resolve("bin"));
        Files.writeString(jdkHome.resolve("bin").resolve("java"), "#!/fake\n");
        Files.writeString(jdkHome.resolve("release"), "JAVA_VERSION=\"25.0.3\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");

        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "[project]\ngroup=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(Lockfile.empty("0.1", "temurin-25.0.3"), project.resolve("jk.lock"));

        var env = new JkEnv(new JdkRegistry(jdksRoot), "/usr/bin:/bin", noGlobalDefault(tempDir));
        var target = env.resolve(project);

        // ProbeSupport canonicalises JDK homes via toRealPath() — on macOS
        // the @TempDir path lives under /var/folders/... which symlinks to
        // /private/var/folders/..., so the expected JAVA_HOME must be the
        // real path, not the raw @TempDir.
        var realJdkHome = jdkHome.toRealPath();
        assertThat(target.isActive()).isTrue();
        assertThat(target.projectRoot()).contains(project);
        assertThat(target.vars().get("JAVA_HOME")).isEqualTo(realJdkHome.toString());
        assertThat(target.vars().get("PATH"))
                .isEqualTo(realJdkHome.resolve("bin") + File.pathSeparator + "/usr/bin:/bin");
    }

    @Test
    void sets_graalvm_home_when_jdk_is_graalvm(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        var jdkHome = jdksRoot.resolve("graalvm-jdk-25");
        Files.createDirectories(jdkHome.resolve("bin"));
        Files.writeString(jdkHome.resolve("bin").resolve("java"), "#!/fake\n");
        Files.writeString(
                jdkHome.resolve("release"),
                "JAVA_VERSION=\"25.0.0\"\nIMPLEMENTOR=\"Oracle Corporation\"\nIMPLEMENTOR_VERSION=\"Oracle GraalVM 25\"\n");

        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "[project]\ngroup=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(Lockfile.empty("0.1", "graalvm-jdk-25"), project.resolve("jk.lock"));

        var env = new JkEnv(new JdkRegistry(jdksRoot), "/usr/bin", noGlobalDefault(tempDir));
        var target = env.resolve(project);

        // Canonicalise: see resolves_jdk_home_from_registry for the macOS
        // /var → /private/var symlink rationale.
        var realJdkHome = jdkHome.toRealPath();
        assertThat(target.vars()).containsEntry("GRAALVM_HOME", realJdkHome.toString());
        assertThat(target.vars()).containsEntry("JAVA_HOME", realJdkHome.toString());
    }

    @Test
    void walks_up_from_subdirectory_to_find_project_root(@TempDir Path tempDir) throws IOException {
        var project = tempDir.resolve("proj");
        var nested = project.resolve("src/main/java/com/example");
        Files.createDirectories(nested);
        Files.writeString(project.resolve("jk.toml"), "[project]\ngroup=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");

        // No JDK pinned → still walks up successfully but yields empty target.
        var found = JkEnv.findProjectRoot(nested);
        assertThat(found).contains(project.toAbsolutePath().normalize());
    }

    @Test
    void unknown_jdk_identifier_yields_empty_target(@TempDir Path tempDir) throws IOException {
        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "[project]\ngroup=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(Lockfile.empty("0.1", "nonexistent-jdk-999"), project.resolve("jk.lock"));

        var env = new JkEnv(new JdkRegistry(tempDir.resolve("jdks")), "/usr/bin", noGlobalDefault(tempDir));
        assertThat(env.resolve(project).isActive()).isFalse();
    }

    @Test
    void falls_back_to_default_jdk_outside_a_project(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        var jdkHome = fakeJdk(jdksRoot.resolve("temurin-25.0.3"));
        var defaults = globalDefaultConfig(tempDir, "temurin-25.0.3");

        // A bare directory with no jk.toml anywhere — yet the configured default
        // JDK still lands on PATH so `java`/`javac` resolve.
        var env = new JkEnv(new JdkRegistry(jdksRoot), "/usr/bin", defaults);
        var target = env.resolve(tempDir);

        var realHome = jdkHome.toRealPath();
        assertThat(target.isActive()).isTrue();
        assertThat(target.projectRoot()).isEmpty();
        assertThat(target.vars().get("JAVA_HOME")).isEqualTo(realHome.toString());
        assertThat(target.vars().get("PATH")).isEqualTo(realHome.resolve("bin") + File.pathSeparator + "/usr/bin");
    }

    @Test
    void falls_back_to_default_when_project_has_no_pin(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        var jdkHome = fakeJdk(jdksRoot.resolve("temurin-25.0.3"));
        var defaults = globalDefaultConfig(tempDir, "temurin-25.0.3");

        var project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), "[project]\ngroup=\"x\"\nname=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(Lockfile.empty("0.1"), project.resolve("jk.lock"));

        var env = new JkEnv(new JdkRegistry(jdksRoot), "/usr/bin", defaults);
        var target = env.resolve(project);

        assertThat(target.isActive()).isTrue(); // no pin → default fills in
        assertThat(target.vars().get("JAVA_HOME"))
                .isEqualTo(jdkHome.toRealPath().toString());
    }

    @Test
    void current_jdk_symlink_wins_over_default(@TempDir Path tempDir) throws IOException {
        var jdksRoot = tempDir.resolve("jdks");
        var current = fakeJdk(jdksRoot.resolve("temurin-26.0.1"));
        fakeJdk(jdksRoot.resolve("temurin-21.0.5")); // the configured default

        var data = tempDir.resolve("jkdata");
        Files.createDirectories(data);
        var configFile = data.resolve("config.toml");
        Files.writeString(configFile, "default-jdk = \"temurin-21.0.5\"\n");
        Files.createSymbolicLink(data.resolve("current-jdk"), current);
        var defaults = new GlobalDefaultJdk(data.resolve("default-jdk"), data.resolve("current-jdk"), configFile);

        var env = new JkEnv(new JdkRegistry(jdksRoot), "/usr/bin", defaults);
        var target = env.resolve(tempDir);

        // current-jdk (26) is honoured ahead of the configured default (21).
        assertThat(target.vars().get("JAVA_HOME"))
                .isEqualTo(current.toRealPath().toString());
    }

    /** A GlobalDefaultJdk pointing at empty, non-existent channels — no current, no default. */
    private static GlobalDefaultJdk noGlobalDefault(Path tempDir) {
        var data = tempDir.resolve("jkdata-empty");
        return new GlobalDefaultJdk(
                data.resolve("default-jdk"), data.resolve("current-jdk"), data.resolve("config.toml"));
    }

    /** A GlobalDefaultJdk whose config pins {@code default-jdk = "<id>"}; no current symlink. */
    private static GlobalDefaultJdk globalDefaultConfig(Path tempDir, String id) throws IOException {
        var data = tempDir.resolve("jkdata");
        Files.createDirectories(data);
        var configFile = data.resolve("config.toml");
        Files.writeString(configFile, "default-jdk = \"" + id + "\"\n");
        return new GlobalDefaultJdk(data.resolve("default-jdk"), data.resolve("current-jdk"), configFile);
    }

    /** Stand up a fake jk-managed JDK install (bin/java + release) and return its home. */
    private static Path fakeJdk(Path home) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake\n");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"25.0.3\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        return home;
    }
}
