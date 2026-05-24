// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.util.Hashing;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

        served.put("/feed/jdks.json.xz", xz(feedJson(archive.length, Hashing.sha256Hex(archive),
                base.resolve("/archives/jdk.tar.gz").toString())));

        int exit = run("jdk", "install", "temurin-21",
                "--jdks-dir", jdksDir.toString(),
                "--feed-url", base.resolve("/feed/jdks.json.xz").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(jdksDir.resolve("temurin-21.0.5").resolve("bin").resolve("java")).exists();
    }

    @Test
    void list_reports_installed_jdks_offline(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-21.0.5"));
        makeJdkInstall(jdks.resolve("temurin-23"));

        String stdout = captureStdout(() -> run("jdk", "list",
                "--offline",
                "--jdks-dir", jdks.toString()));
        // Grouped by major desc: 23 first, then 21.0.5.
        int idx23 = stdout.indexOf("temurin-23");
        int idx21 = stdout.indexOf("temurin-21.0.5");
        assertThat(idx23).isGreaterThanOrEqualTo(0);
        assertThat(idx21).isGreaterThan(idx23);
        // Both are installed (no system default), so status column shows "installed".
        assertThat(stdout).contains("installed");
    }

    @Test
    void list_combines_installed_and_available_from_catalog(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-21.0.5"));

        byte[] dummyArchive = "stub".getBytes(StandardCharsets.UTF_8);
        served.put("/feed/jdks.json.xz", xz(multiEntryFeedJson(dummyArchive.length,
                Hashing.sha256Hex(dummyArchive), base.toString())));

        String stdout = captureStdout(() -> run("jdk", "list",
                "--jdks-dir", jdks.toString(),
                "--feed-url", base.resolve("/feed/jdks.json.xz").toString()));

        // Higher major (catalog-only) appears first; installed row follows.
        int idxAvailable25 = stdout.indexOf("temurin-25");
        int idxInstalled21 = stdout.indexOf("temurin-21.0.5");
        assertThat(idxAvailable25).isGreaterThanOrEqualTo(0);
        assertThat(idxInstalled21).isGreaterThan(idxAvailable25);
        // Installed row's status column reads "installed".
        assertThat(stdout).contains("installed");
    }

    @Test
    void installed_drops_catalog_only_rows(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-21.0.5"));

        byte[] dummyArchive = "stub".getBytes(StandardCharsets.UTF_8);
        served.put("/feed/jdks.json.xz", xz(multiEntryFeedJson(dummyArchive.length,
                Hashing.sha256Hex(dummyArchive), base.toString())));

        String stdout = captureStdout(() -> run("jdk", "installed",
                "--jdks-dir", jdks.toString(),
                "--feed-url", base.resolve("/feed/jdks.json.xz").toString()));

        // Installed row is present; the catalog-only "temurin-25" row is filtered out.
        assertThat(stdout).contains("temurin-21.0.5");
        assertThat(stdout).contains("installed");
        assertThat(stdout).doesNotContain("temurin-25");
    }

    @Test
    void pin_writes_jk_version(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-21.0.5"));

        int exit = run("jdk", "pin", "temurin-21",
                "-C", tempDir.toString(),
                "--jdks-dir", jdks.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tempDir.resolve(".jk-version")).trim())
                .isEqualTo("temurin-21.0.5");
    }

    @Test
    void uninstall_removes_jdk_dir(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        Path victim = jdks.resolve("temurin-21.0.5");
        makeJdkInstall(victim);

        int exit = run("jdk", "uninstall", "temurin-21.0.5",
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
        Path jdkHome = jdks.resolve("temurin-21.0.5");
        makeJdkInstall(jdkHome);
        Files.writeString(tempDir.resolve(".jk-version"), "temurin-21.0.5\n");

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
        Path bin = tempDir.resolve(".local/bin");

        int exit = run("jdk", "update-shell",
                "--shell", "bash",
                "--home", tempDir.toString(),
                "--bin-dir", bin.toString());
        assertThat(exit).isEqualTo(0);

        String body = Files.readString(rc);
        assertThat(body).contains("# user content");
        assertThat(body).contains(JdkUpdateShellCommand.MARKER);
        assertThat(body).contains("export PATH=\"" + bin + ":$PATH\"");
    }

    @Test
    void update_shell_writes_zshenv_not_zshrc(@TempDir Path tempDir) throws IOException {
        int exit = run("jdk", "update-shell",
                "--shell", "zsh",
                "--home", tempDir.toString(),
                "--bin-dir", tempDir.resolve(".local/bin").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve(".zshenv")).exists();
        assertThat(tempDir.resolve(".zshrc")).doesNotExist();
    }

    @Test
    void update_shell_uses_fish_add_path_for_fish(@TempDir Path tempDir) throws IOException {
        Path bin = tempDir.resolve(".local/bin");
        int exit = run("jdk", "update-shell",
                "--shell", "fish",
                "--home", tempDir.toString(),
                "--bin-dir", bin.toString());
        assertThat(exit).isEqualTo(0);
        Path fishConf = tempDir.resolve(".config/fish/conf.d/jk.fish");
        assertThat(fishConf).exists();
        assertThat(Files.readString(fishConf))
                .contains("fish_add_path \"" + bin + "\"");
    }

    @Test
    void update_shell_is_idempotent(@TempDir Path tempDir) throws IOException {
        Path bin = tempDir.resolve(".local/bin");
        int first = run("jdk", "update-shell",
                "--shell", "bash",
                "--home", tempDir.toString(),
                "--bin-dir", bin.toString());
        assertThat(first).isEqualTo(0);
        String afterFirst = Files.readString(tempDir.resolve(".bashrc"));

        int second = run("jdk", "update-shell",
                "--shell", "bash",
                "--home", tempDir.toString(),
                "--bin-dir", bin.toString());
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

    @Test
    void jdks_is_an_alias_for_jdk(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-21.0.5"));

        String stdout = captureStdout(() -> run("jdks", "list",
                "--offline",
                "--jdks-dir", jdks.toString()));
        assertThat(stdout).contains("temurin-21.0.5");
    }

    @Test
    void jdk_add_is_an_alias_for_install() {
        // `add` reaches JdkInstallCommand. We don't drive a full install
        // here (covered elsewhere); just verify the alias resolves and the
        // missing-arg picocli error path is the same as for `install`.
        int viaInstall = run("jdk", "install");
        int viaAdd = run("jdk", "add");
        assertThat(viaAdd).isEqualTo(viaInstall);
    }

    @Test
    void jdk_remove_is_an_alias_for_uninstall(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        Path victim = jdks.resolve("temurin-21.0.5");
        makeJdkInstall(victim);

        int exit = run("jdk", "remove", "temurin-21.0.5",
                "--jdks-dir", jdks.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(victim).doesNotExist();
    }

    // --- helpers ------------------------------------------------------------

    private static int run(String... args) {
        return Jk.execute(args);
    }

    private static String captureStdout(IntSupplier body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        System.setOut(new PrintStream(out));
        try { body.getAsInt(); } finally { System.setOut(origOut); }
        return out.toString(StandardCharsets.UTF_8);
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

    private static byte[] xz(String json) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XZCompressorOutputStream xz = new XZCompressorOutputStream(out)) {
            xz.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static void makeJdkInstall(Path home) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
    }

    private static String feedJson(long size, String sha256, String url) {
        return """
                {
                  "jdks": [
                    {
                      "vendor": "Eclipse",
                      "product": "Temurin",
                      "default": true,
                      "jdk_version_major": 21,
                      "jdk_version": "21.0.5",
                      "suggested_sdk_name": "temurin-21",
                      "shared_index_aliases": ["temurin-21.0.5", "temurin-21", "21.0.5", "21"],
                      "packages": [
                        {
                          "os": "OS",
                          "arch": "ARCH",
                          "version": "21.0.5",
                          "url": "URL",
                          "package_type": "targz",
                          "package_to_java_home_prefix": "",
                          "archive_file_name": "temurin.tar.gz",
                          "install_folder_name": "temurin-21.0.5",
                          "archive_size": SIZE,
                          "sha256": "SHA"
                        }
                      ]
                    }
                  ]
                }
                """
                .replace("OS", HostPlatform.currentOs())
                .replace("ARCH", HostPlatform.currentArch())
                .replace("URL", url)
                .replace("SIZE", Long.toString(size))
                .replace("SHA", sha256);
    }

    /** Feed JSON with a Temurin 25 entry alongside the 21 entry — used by `list`. */
    private static String multiEntryFeedJson(long size, String sha256, String baseUrl) {
        return feedJson(size, sha256, baseUrl + "/dummy.tar.gz")
                .replaceFirst("\"jdks\": \\[\\s*", "\"jdks\": [\n"
                        + entryJson("Eclipse", "Temurin", "temurin-25", 25, "25.0.1",
                                false, size, sha256, baseUrl)
                        + ",\n");
    }

    private static String entryJson(String vendor, String product,
                                    String suggestedSdkName, int major, String version,
                                    boolean preview, long size, String sha256, String baseUrl) {
        return """
                {
                  "vendor": "VENDOR",
                  "product": "PRODUCT",
                  "default": false,
                  "preview": PREVIEW,
                  "jdk_version_major": MAJOR,
                  "jdk_version": "VERSION",
                  "suggested_sdk_name": "SDK_NAME",
                  "shared_index_aliases": ["SDK_NAME", "VERSION", "MAJOR"],
                  "packages": [
                    {
                      "os": "OS",
                      "arch": "ARCH",
                      "version": "VERSION",
                      "url": "URL",
                      "package_type": "targz",
                      "package_to_java_home_prefix": "",
                      "archive_file_name": "x.tar.gz",
                      "install_folder_name": "SDK_NAME.VERSION_SUFFIX",
                      "archive_size": SIZE,
                      "sha256": "SHA"
                    }
                  ]
                }
                """
                .replace("VENDOR", vendor)
                .replace("PRODUCT", product)
                .replace("SDK_NAME", suggestedSdkName)
                .replace("VERSION_SUFFIX", version)
                .replace("MAJOR", Integer.toString(major))
                .replace("VERSION", version)
                .replace("PREVIEW", Boolean.toString(preview))
                .replace("OS", HostPlatform.currentOs())
                .replace("ARCH", HostPlatform.currentArch())
                .replace("URL", baseUrl + "/dummy.tar.gz")
                .replace("SIZE", Long.toString(size))
                .replace("SHA", sha256);
    }
}
