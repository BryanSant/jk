// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class EnvShellCommandTest {

    @Test
    void env_prints_exports_for_project_pinned_jdk(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("jdks");
        Path home = jdks.resolve("21.0.5-tem-x64-linux");
        makeJdkFixture(home, "21.0.5");
        Path project = scaffoldProject(tempDir.resolve("project"), "21.0.5-tem-x64-linux");

        String stdout = captureStdout(() -> run(
                "env",
                "-C", project.toString(),
                "--jdks-dir", jdks.toString()));

        assertThat(stdout).contains("export JAVA_HOME=" + home);
        // PATH now expands to the JDK bin prepended to the current PATH —
        // a one-shot snapshot rather than the deferred `:$PATH` form, which
        // matches how `jk hook-env` emits PATH.
        assertThat(stdout).contains("export PATH=");
        assertThat(stdout).contains(home.resolve("bin").toString());
    }

    @Test
    void env_errors_when_lockfile_has_no_pinned_jdk(@TempDir Path tempDir) throws IOException {
        // jk.toml present but jk.lock has no `jdk = ...` line.
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"),
                "[project]\ngroup=\"x\"\nartifact=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(Lockfile.empty("0.1"), project.resolve("jk.lock"));

        int exit = run("env",
                "-C", project.toString(),
                "--jdks-dir", tempDir.resolve("jdks").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void env_errors_when_no_project(@TempDir Path tempDir) {
        // Empty dir → no jk.toml anywhere upstream.
        Path project = tempDir.resolve("not-a-project");
        try {
            Files.createDirectories(project);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        int exit = run("env",
                "-C", project.toString(),
                "--jdks-dir", tempDir.resolve("jdks").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void env_shell_quotes_paths_with_spaces(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("home dir/jdks");
        Path home = jdks.resolve("21.0.5-tem-x64-linux");
        makeJdkFixture(home, "21.0.5");
        Path project = scaffoldProject(tempDir.resolve("project"), "21.0.5-tem-x64-linux");

        String stdout = captureStdout(() -> run(
                "env",
                "-C", project.toString(),
                "--jdks-dir", jdks.toString()));

        // The path has a space, so single-quoting must kick in.
        assertThat(stdout).contains("export JAVA_HOME='");
    }

    @Test
    void env_emits_graalvm_home_when_jdk_is_graalvm(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("jdks");
        Path home = jdks.resolve("graalvm-jdk-25");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(home.resolve("release"),
                "JAVA_VERSION=\"25.0.0\"\nIMPLEMENTOR=\"Oracle Corporation\"\nIMPLEMENTOR_VERSION=\"Oracle GraalVM 25\"\n");
        Path project = scaffoldProject(tempDir.resolve("project"), "graalvm-jdk-25");

        String stdout = captureStdout(() -> run(
                "env",
                "-C", project.toString(),
                "--jdks-dir", jdks.toString()));

        assertThat(stdout).contains("export JAVA_HOME=" + home);
        assertThat(stdout).contains("export GRAALVM_HOME=" + home);
    }

    @Test
    void shell_errors_when_no_pin(@TempDir Path tempDir) throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        int exit = run("shell",
                "-C", project.toString(),
                "--jdks-dir", tempDir.resolve("jdks").toString());
        assertThat(exit).isEqualTo(2);
    }

    /** Scaffold a minimal jk project that pins {@code identifier} via jk.lock. */
    private static Path scaffoldProject(Path project, String identifier) throws IOException {
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"),
                "[project]\ngroup=\"x\"\nartifact=\"y\"\nversion=\"1.0\"\n");
        LockfileWriter.write(Lockfile.empty("0.1", identifier), project.resolve("jk.lock"));
        return project;
    }

    private static void makeJdkFixture(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }

    private static String captureStdout(IntSupplier body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));
        try {
            body.getAsInt();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
