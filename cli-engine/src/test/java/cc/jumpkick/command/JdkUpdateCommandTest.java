// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.util.Hashing;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration tests for {@code jk jdk update [spec]}. */
class JdkUpdateCommandTest {

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
    void stop() {
        server.stop(0);
    }

    @Test
    void updates_to_latest_point_release_and_removes_old(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-25.0.2"), "25.0.2", "Eclipse Adoptium");
        serveFeed(tempDir, vendorEntry("Eclipse", "Temurin", "temurin", 25, "25.0.3"));

        int exit = run(
                "jdk",
                "update",
                "25",
                "--yes",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(jdks.resolve("temurin-25.0.3").resolve("bin").resolve("java"))
                .exists();
        assertThat(jdks.resolve("temurin-25.0.2")).doesNotExist();
    }

    @Test
    void bare_major_updates_all_vendors(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-25.0.2"), "25.0.2", "Eclipse Adoptium");
        makeJdkInstall(jdks.resolve("corretto-25.0.1"), "25.0.1", "Amazon.com Inc.");
        serveFeed(
                tempDir,
                vendorEntry("Eclipse", "Temurin", "temurin", 25, "25.0.3"),
                vendorEntry("Amazon", "Corretto", "corretto", 25, "25.0.3"));

        int exit = run(
                "jdk",
                "update",
                "25",
                "--yes",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(jdks.resolve("temurin-25.0.3").resolve("bin").resolve("java"))
                .exists();
        assertThat(jdks.resolve("corretto-25.0.3").resolve("bin").resolve("java"))
                .exists();
        assertThat(jdks.resolve("temurin-25.0.2")).doesNotExist();
        assertThat(jdks.resolve("corretto-25.0.1")).doesNotExist();
    }

    @Test
    void vendor_spec_limits_to_that_vendor(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-25.0.2"), "25.0.2", "Eclipse Adoptium");
        makeJdkInstall(jdks.resolve("corretto-25.0.1"), "25.0.1", "Amazon.com Inc.");
        serveFeed(
                tempDir,
                vendorEntry("Eclipse", "Temurin", "temurin", 25, "25.0.3"),
                vendorEntry("Amazon", "Corretto", "corretto", 25, "25.0.3"));

        int exit = run(
                "jdk",
                "update",
                "temurin",
                "--yes",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(jdks.resolve("temurin-25.0.3").resolve("bin").resolve("java"))
                .exists();
        assertThat(jdks.resolve("temurin-25.0.2")).doesNotExist();
        // Corretto untouched.
        assertThat(jdks.resolve("corretto-25.0.1").resolve("bin").resolve("java"))
                .exists();
        assertThat(jdks.resolve("corretto-25.0.3")).doesNotExist();
    }

    @Test
    void never_bumps_the_major(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-25.0.2"), "25.0.2", "Eclipse Adoptium");
        // Feed has a newer point release AND a newer major — only the point release applies.
        serveFeed(
                tempDir,
                vendorEntry("Eclipse", "Temurin", "temurin", 25, "25.0.3"),
                vendorEntry("Eclipse", "Temurin", "temurin", 26, "26.0.1"));

        int exit = run(
                "jdk",
                "update",
                "25",
                "--yes",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(jdks.resolve("temurin-25.0.3").resolve("bin").resolve("java"))
                .exists();
        assertThat(jdks.resolve("temurin-26.0.1")).doesNotExist();
    }

    @Test
    void already_latest_reports_up_to_date(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-25.0.3"), "25.0.3", "Eclipse Adoptium");
        serveFeed(tempDir, vendorEntry("Eclipse", "Temurin", "temurin", 25, "25.0.3"));

        String stdout = captureStdout(() -> run(
                "jdk",
                "update",
                "--yes",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString()));
        assertThat(stdout).contains("up to date");
        assertThat(jdks.resolve("temurin-25.0.3").resolve("bin").resolve("java"))
                .exists();
    }

    @Test
    void no_managed_jdks_is_a_noop(@TempDir Path tempDir) {
        Path jdks = tempDir.resolve("jdks");
        String stdout = captureStdout(() -> run("jdk", "update", "--yes", "--jdks-dir", jdks.toString()));
        assertThat(stdout).contains("no jk-managed JDKs installed");
    }

    @Test
    void declining_the_prompt_changes_nothing(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-25.0.2"), "25.0.2", "Eclipse Adoptium");
        serveFeed(tempDir, vendorEntry("Eclipse", "Temurin", "temurin", 25, "25.0.3"));

        String stdout = withStdin(
                "n\n",
                () -> captureStdout(() -> run(
                        "jdk",
                        "update",
                        "25",
                        "--jdks-dir",
                        jdks.toString(),
                        "--feed-url",
                        base.resolve("/feed/jdks.json").toString())));
        assertThat(stdout).contains("Aborted");
        assertThat(jdks.resolve("temurin-25.0.2").resolve("bin").resolve("java"))
                .exists();
        assertThat(jdks.resolve("temurin-25.0.3")).doesNotExist();
    }

    @Test
    void upgrade_is_an_alias_for_update(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-25.0.2"), "25.0.2", "Eclipse Adoptium");
        serveFeed(tempDir, vendorEntry("Eclipse", "Temurin", "temurin", 25, "25.0.3"));

        int exit = run(
                "jdk",
                "upgrade",
                "25",
                "--yes",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(jdks.resolve("temurin-25.0.3").resolve("bin").resolve("java"))
                .exists();
    }

    // --- fixtures -----------------------------------------------------------

    private void serveFeed(Path tempDir, EntrySpec... specs) throws Exception {
        List<String> entries = new ArrayList<>();
        for (EntrySpec s : specs) {
            byte[] archive = buildTarGz(
                    tempDir,
                    s.installFolder(),
                    Map.of("bin/java", "#!/fake/java", "release", "JAVA_VERSION=\"" + s.version() + "\"\n"));
            String archivePath = "/archives/" + s.installFolder() + ".tar.gz";
            served.put(archivePath, archive);
            entries.add(entryJson(
                    s,
                    archive.length,
                    Hashing.sha256Hex(archive),
                    base.resolve(archivePath).toString()));
        }
        String feed = "{\n  \"jdks\": [\n" + String.join(",\n", entries) + "\n  ]\n}";
        served.put("/feed/jdks.json", feed.getBytes(StandardCharsets.UTF_8));
    }

    private record EntrySpec(String vendor, String product, String sdkPrefix, int major, String version) {
        String installFolder() {
            return sdkPrefix + "-" + version;
        }
    }

    private static EntrySpec vendorEntry(String vendor, String product, String sdkPrefix, int major, String version) {
        return new EntrySpec(vendor, product, sdkPrefix, major, version);
    }

    private static String entryJson(EntrySpec s, long size, String sha256, String url) {
        return """
                {
                  "vendor": "VENDOR",
                  "product": "PRODUCT",
                  "default": false,
                  "jdk_version_major": MAJOR,
                  "jdk_version": "VERSION",
                  "suggested_sdk_name": "PREFIX-MAJOR",
                  "shared_index_aliases": ["PREFIX-VERSION", "PREFIX-MAJOR", "VERSION", "MAJOR"],
                  "packages": [
                    {
                      "os": "OS",
                      "arch": "ARCH",
                      "version": "VERSION",
                      "url": "URL",
                      "package_type": "targz",
                      "package_to_java_home_prefix": "",
                      "archive_file_name": "jdk.tar.gz",
                      "install_folder_name": "FOLDER",
                      "archive_size": SIZE,
                      "sha256": "SHA"
                    }
                  ]
                }
                """.replace("VENDOR", s.vendor())
                .replace("PRODUCT", s.product())
                .replace("PREFIX", s.sdkPrefix())
                .replace("FOLDER", s.installFolder())
                .replace("VERSION", s.version())
                .replace("MAJOR", Integer.toString(s.major()))
                .replace("OS", HostPlatform.currentOs())
                .replace("ARCH", HostPlatform.currentArch())
                .replace("URL", url)
                .replace("SIZE", Long.toString(size))
                .replace("SHA", sha256);
    }

    private static byte[] buildTarGz(Path tempDir, String topLevelDir, Map<String, String> entries) throws Exception {
        Path workdir = tempDir.resolve("fixture-" + System.nanoTime());
        Path root = workdir.resolve(topLevelDir);
        Files.createDirectories(root);
        for (var entry : entries.entrySet()) {
            Path target = root.resolve(entry.getKey());
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue());
        }
        Path archivePath = workdir.resolve("archive.tar.gz");
        ProcessBuilder pb =
                new ProcessBuilder("tar", "czf", archivePath.toString(), "-C", workdir.toString(), topLevelDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (p.waitFor() != 0) {
            throw new RuntimeException(
                    "tar fixture build failed: " + new String(p.getInputStream().readAllBytes()));
        }
        return Files.readAllBytes(archivePath);
    }

    private static void makeJdkInstall(Path home, String version, String implementor) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"" + implementor + "\"\n");
    }

    private static int run(String... args) {
        return Jk.execute(args);
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

    private static <T> T withStdin(String input, Supplier<T> body) {
        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        try {
            return body.get();
        } finally {
            System.setIn(origIn);
        }
    }
}
