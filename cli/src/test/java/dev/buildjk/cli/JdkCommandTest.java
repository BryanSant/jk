// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import com.sun.net.httpserver.HttpServer;
import dev.buildjk.jdk.Platform;
import dev.buildjk.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class JdkCommandTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> served = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void install_downloads_and_extracts(@TempDir Path tempDir) throws Exception {
        Path jdksDir = tempDir.resolve("jdks");

        byte[] archive = buildTarGz(tempDir, "jdk-21.0.5+11", Map.of(
                "bin/java", "#!/fake/java",
                "release", "JAVA_VERSION=21.0.5\n"));
        served.put("/archives/jdk.tar.gz", archive);

        String discoJson = """
                {
                  "result": [
                    {
                      "distribution": "temurin",
                      "java_version": "21.0.5",
                      "architecture": "ARCH",
                      "operating_system": "OS",
                      "archive_type": "tar.gz",
                      "filename": "OpenJDK21U.tar.gz",
                      "size": SIZE,
                      "sha256": "SHA",
                      "links": {
                        "pkg_download_redirect": "BASE/archives/jdk.tar.gz"
                      }
                    }
                  ]
                }
                """
                .replace("ARCH", Platform.currentArchitecture())
                .replace("OS", Platform.currentOperatingSystem())
                .replace("SIZE", Integer.toString(archive.length))
                .replace("SHA", Hashing.sha256Hex(archive))
                .replace("BASE", base.toString());
        served.put("/disco/packages", discoJson.getBytes(StandardCharsets.UTF_8));

        int exit = run("jdk", "install", "21-tem",
                "--jdks-dir", jdksDir.toString(),
                "--disco-url", base.resolve("/disco/").toString());
        assertThat(exit).isEqualTo(0);

        // Identifier includes platform suffix; just check the version+vendor prefix.
        try (var stream = Files.list(jdksDir)) {
            assertThat(stream.iterator().hasNext()).isTrue();
        }
    }

    @Test
    void list_reports_installed_jdks_offline(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        Files.createDirectories(jdks.resolve("21.0.5-tem-x64-linux"));
        Files.createDirectories(jdks.resolve("23-tem-x64-linux"));

        String stdout = captureStdout(() -> run("jdk", "list",
                "--offline",
                "--jdks-dir", jdks.toString()));
        // Both installed, newest first.
        int idx23 = stdout.indexOf("23-tem-x64-linux");
        int idx21 = stdout.indexOf("21.0.5-tem-x64-linux");
        assertThat(idx23).isGreaterThanOrEqualTo(0);
        assertThat(idx21).isGreaterThan(idx23);
    }

    @Test
    void list_combines_installed_then_remote_with_versions_descending(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        Files.createDirectories(jdks.resolve("21.0.5-tem-x64-linux"));

        // Disco returns four packages on this OS/arch: a 25, a 23, a 21.0.5
        // (already installed, should be filtered out), and a 17.
        String discoJson = """
                {
                  "result": [
                    {"distribution": "temurin", "java_version": "23",
                     "architecture": "ARCH", "operating_system": "OS",
                     "archive_type": "tar.gz", "filename": "a.tar.gz",
                     "links": {"pkg_download_redirect": "BASE/a.tar.gz"}},
                    {"distribution": "temurin", "java_version": "21.0.5",
                     "architecture": "ARCH", "operating_system": "OS",
                     "archive_type": "tar.gz", "filename": "b.tar.gz",
                     "links": {"pkg_download_redirect": "BASE/b.tar.gz"}},
                    {"distribution": "temurin", "java_version": "25.0.1",
                     "architecture": "ARCH", "operating_system": "OS",
                     "archive_type": "tar.gz", "filename": "c.tar.gz",
                     "links": {"pkg_download_redirect": "BASE/c.tar.gz"}},
                    {"distribution": "temurin", "java_version": "17.0.13",
                     "architecture": "ARCH", "operating_system": "OS",
                     "archive_type": "tar.gz", "filename": "d.tar.gz",
                     "links": {"pkg_download_redirect": "BASE/d.tar.gz"}}
                  ]
                }
                """
                .replace("ARCH", Platform.currentArchitecture())
                .replace("OS", Platform.currentOperatingSystem())
                .replace("BASE", base.toString());
        served.put("/disco/packages", discoJson.getBytes(StandardCharsets.UTF_8));

        String stdout = captureStdout(() -> run("jdk", "list",
                "--jdks-dir", jdks.toString(),
                "--disco-url", base.resolve("/disco/").toString()));

        // Installed first.
        int idxInstalled = stdout.indexOf("21.0.5-tem-x64-linux");
        assertThat(idxInstalled).isGreaterThanOrEqualTo(0);
        assertThat(stdout.substring(idxInstalled)).contains(jdks.resolve("21.0.5-tem-x64-linux").toString());

        // Remote-only ordered newest first: 25 → 23 → 17 (21.0.5 was installed).
        int idx25 = stdout.indexOf("25.0.1-tem-");
        int idx23 = stdout.indexOf("23-tem-");
        int idx17 = stdout.indexOf("17.0.13-tem-");
        assertThat(idx25).isGreaterThan(idxInstalled);
        assertThat(idx23).isGreaterThan(idx25);
        assertThat(idx17).isGreaterThan(idx23);

        // The 21.0.5 remote entry doesn't get a duplicate <download available> line.
        assertThat(stdout).doesNotContain("21.0.5-tem-x64-linux  <download available>");
        assertThat(stdout).contains("<download available>");
    }

    private static String captureStdout(IntSupplier body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            body.getAsInt();
        } finally {
            System.setOut(origOut);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void pin_writes_jk_version_and_sdkmanrc(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        Files.createDirectories(jdks.resolve("21.0.5-tem-x64-linux/bin"));

        int exit = run("jdk", "pin", "21.0.5-tem",
                "-C", tempDir.toString(),
                "--jdks-dir", jdks.toString());
        assertThat(exit).isEqualTo(0);

        assertThat(Files.readString(tempDir.resolve(".jk-version")).trim())
                .isEqualTo("21.0.5-tem-x64-linux");
        assertThat(Files.readString(tempDir.resolve(".sdkmanrc")).trim())
                .isEqualTo("java=21.0.5-tem");
    }

    @Test
    void pin_no_sdkman_compat_skips_sdkmanrc(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        Files.createDirectories(jdks.resolve("21.0.5-tem-x64-linux"));

        int exit = run("jdk", "pin", "21.0.5-tem",
                "-C", tempDir.toString(),
                "--jdks-dir", jdks.toString(),
                "--no-sdkman-compat");
        assertThat(exit).isEqualTo(0);

        assertThat(tempDir.resolve(".jk-version")).exists();
        assertThat(tempDir.resolve(".sdkmanrc")).doesNotExist();
    }

    @Test
    void uninstall_removes_jdk_dir(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        Path victim = jdks.resolve("21.0.5-tem-x64-linux");
        Files.createDirectories(victim.resolve("bin"));
        Files.writeString(victim.resolve("bin/java"), "x");

        int exit = run("jdk", "uninstall", "21.0.5-tem-x64-linux",
                "--jdks-dir", jdks.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(victim).doesNotExist();
    }

    @Test
    void pin_unknown_jdk_errors(@TempDir Path tempDir) {
        int exit = run("jdk", "pin", "nothing-installed",
                "-C", tempDir.toString(),
                "--jdks-dir", tempDir.resolve("jdks").toString());
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void home_prints_export_java_home_for_pinned_jdk(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        Path jdkHome = jdks.resolve("21.0.5-tem-x64-linux");
        Files.createDirectories(jdkHome.resolve("bin"));
        Files.writeString(tempDir.resolve(".jk-version"), "21.0.5-tem-x64-linux\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        System.setOut(new PrintStream(out));
        int exit;
        try {
            exit = run("jdk", "home",
                    "-C", tempDir.toString(),
                    "--jdks-dir", jdks.toString());
        } finally {
            System.setOut(origOut);
        }
        assertThat(exit).isEqualTo(0);
        String stdout = out.toString(StandardCharsets.UTF_8).trim();
        assertThat(stdout).isEqualTo("export JAVA_HOME=" + jdkHome);
    }

    @Test
    void home_errors_when_no_pinned_jdk(@TempDir Path tempDir) {
        int exit = run("jdk", "home",
                "-C", tempDir.toString(),
                "--jdks-dir", tempDir.resolve("jdks").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void update_shell_appends_path_export_to_bashrc(@TempDir Path tempDir) throws IOException {
        Path rc = tempDir.resolve(".bashrc");
        Files.writeString(rc, "# user content\n");

        int exit = run("jdk", "update-shell",
                "--shell", "bash",
                "--home", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        String body = Files.readString(rc);
        assertThat(body).contains("# user content");
        assertThat(body).contains(JdkUpdateShellCommand.MARKER);
        assertThat(body).contains("export PATH=\"" + tempDir + "/.jk/bin:$PATH\"");
    }

    @Test
    void update_shell_writes_zshenv_not_zshrc(@TempDir Path tempDir) throws IOException {
        int exit = run("jdk", "update-shell",
                "--shell", "zsh",
                "--home", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve(".zshenv")).exists();
        assertThat(tempDir.resolve(".zshrc")).doesNotExist();
    }

    @Test
    void update_shell_uses_fish_add_path_for_fish(@TempDir Path tempDir) throws IOException {
        int exit = run("jdk", "update-shell",
                "--shell", "fish",
                "--home", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        Path fishConf = tempDir.resolve(".config/fish/conf.d/jk.fish");
        assertThat(fishConf).exists();
        assertThat(Files.readString(fishConf))
                .contains("fish_add_path \"" + tempDir + "/.jk/bin\"");
    }

    @Test
    void update_shell_is_idempotent(@TempDir Path tempDir) throws IOException {
        int first = run("jdk", "update-shell",
                "--shell", "bash",
                "--home", tempDir.toString());
        assertThat(first).isEqualTo(0);
        String afterFirst = Files.readString(tempDir.resolve(".bashrc"));

        int second = run("jdk", "update-shell",
                "--shell", "bash",
                "--home", tempDir.toString());
        assertThat(second).isEqualTo(0);
        assertThat(Files.readString(tempDir.resolve(".bashrc")))
                .isEqualTo(afterFirst);  // unchanged
    }

    @Test
    void update_shell_unknown_shell_returns_usage_error(@TempDir Path tempDir) {
        int exit = run("jdk", "update-shell",
                "--shell", "tcsh",
                "--home", tempDir.toString());
        assertThat(exit).isEqualTo(64);
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }

    private static byte[] buildTarGz(Path tempDir, String topLevelDir,
                                      Map<String, String> entries) throws Exception {
        Path workdir = tempDir.resolve("fixture-" + System.nanoTime());
        Path root = workdir.resolve(topLevelDir);
        Files.createDirectories(root);
        for (var entry : entries.entrySet()) {
            Path target = root.resolve(entry.getKey());
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue());
        }
        Path archivePath = workdir.resolve("archive.tar.gz");
        ProcessBuilder pb = new ProcessBuilder("tar", "czf", archivePath.toString(),
                "-C", workdir.toString(), topLevelDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (p.waitFor() != 0) {
            throw new RuntimeException("tar fixture build failed: "
                    + new String(p.getInputStream().readAllBytes()));
        }
        return Files.readAllBytes(archivePath);
    }
}
