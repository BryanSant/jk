// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cli.Jk;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.util.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration tests for {@code jk jdk ensure <spec>}. */
class JdkEnsureCommandTest {

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
    void bare_major_satisfied_by_installed_point_release(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-25.0.2"), "25.0.2");

        // Fast path: an installed 25.x satisfies `25`. No --feed-url → proves
        // the resolution is offline.
        String stdout = captureStdout(() -> run("jdk", "ensure", "25", "--jdks-dir", jdks.toString()));
        assertThat(stdout).contains("temurin-25.0.2").contains("is available at");
        assertThat(jdks.resolve("temurin-25.0.3")).doesNotExist();
    }

    @Test
    void full_version_installs_when_only_older_present(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-25.0.2"), "25.0.2");
        serveFeed(tempDir, feedEntry(25, "25.0.3", true));

        // 25.0.2 < 25.0.3 floor → install the latest 25 (25.0.3) from the feed.
        int exit = run(
                "jdk",
                "ensure",
                "25.0.3",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(jdks.resolve("temurin-25.0.3").resolve("bin").resolve("java"))
                .exists();
    }

    @Test
    void full_version_satisfied_by_newer_installed(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-25.0.4"), "25.0.4");

        // 25.0.4 >= 25.0.3 floor → satisfied, no install, offline.
        String stdout = captureStdout(() -> run("jdk", "ensure", "25.0.3", "--jdks-dir", jdks.toString()));
        assertThat(stdout).contains("temurin-25.0.4").contains("is available at");
        assertThat(jdks.resolve("temurin-25.0.3")).doesNotExist();
    }

    @Test
    void lts_upgrades_from_older_lts_point_release(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-25.0.2"), "25.0.2");
        // 25 is LTS, 26 is GA. lts → latest 25 point release = 25.0.3.
        serveFeed(tempDir, feedEntry(25, "25.0.3", true), feedEntry(26, "26.0.1", true));

        int exit = run(
                "jdk",
                "ensure",
                "lts",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString());
        assertThat(exit).isEqualTo(0);
        // Older LTS point release didn't satisfy `lts` → 25.0.3 installed.
        assertThat(jdks.resolve("temurin-25.0.3").resolve("bin").resolve("java"))
                .exists();
    }

    @Test
    void latest_resolves_highest_major_ga(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        serveFeed(tempDir, feedEntry(25, "25.0.3", true), feedEntry(26, "26.0.1", true));

        int exit = run(
                "jdk",
                "ensure",
                "latest",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(jdks.resolve("temurin-26.0.1").resolve("bin").resolve("java"))
                .exists();
    }

    @Test
    void unsatisfiable_spec_falls_back_to_latest_lts(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        serveFeed(tempDir, feedEntry(25, "25.0.3", true));

        String stdout = captureStdout(() -> run(
                "jdk",
                "ensure",
                "99",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString()));
        assertThat(stdout)
                .contains("no JDK matches")
                .contains("installing the latest LTS")
                .contains("instead")
                .contains("is available at");
        assertThat(jdks.resolve("temurin-25.0.3").resolve("bin").resolve("java"))
                .exists();
    }

    @Test
    void bare_major_prefers_temurin_over_feed_default(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        // The feed marks Oracle OpenJDK as default-for-major 26; Temurin 26 is
        // present but NOT the default. `ensure 26` (no vendor named) must still
        // install Temurin, and announce it before downloading.
        serveFeed(
                tempDir,
                feedEntry(26, "26.0.1", false),
                vendorEntry("Oracle", "OpenJDK", "openjdk", 26, "26.0.1", true));

        String stdout = captureStdout(() -> run(
                "jdk",
                "ensure",
                "26",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString()));

        assertThat(stdout)
                .contains("Unable to locate a suitable JDK")
                .contains("Installing")
                .contains("Temurin");
        assertThat(jdks.resolve("temurin-26.0.1").resolve("bin").resolve("java"))
                .exists();
        assertThat(jdks.resolve("openjdk-26.0.1")).doesNotExist();
    }

    @Test
    void native_installs_latest_oracle_graalvm(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        // Temurin 25 is the default-flagged GA, but `native` must install the
        // Oracle GraalVM, not Temurin.
        serveFeed(
                tempDir,
                feedEntry(25, "25.0.3", true),
                vendorEntry("Oracle", "GraalVM", "graalvm-jdk", 25, "25", false));

        int exit = run(
                "jdk",
                "ensure",
                "native",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString());
        assertThat(exit).isEqualTo(0);
        // jk owns the on-disk name: <vendor>-<version> via jbPrefix (here version == major).
        assertThat(jdks.resolve("graalvm-25").resolve("bin").resolve("java")).exists();
        assertThat(jdks.resolve("temurin-25.0.3")).doesNotExist();
    }

    @Test
    void native_satisfied_by_installed_graalvm(@TempDir Path tempDir) throws Exception {
        Path jdks = tempDir.resolve("jdks");
        makeGraalvmInstall(jdks.resolve("graalvm-jdk-25"), "25");
        serveFeed(tempDir, vendorEntry("Oracle", "GraalVM", "graalvm-jdk", 25, "25", false));

        String stdout = captureStdout(() -> run(
                "jdk",
                "ensure",
                "native",
                "--jdks-dir",
                jdks.toString(),
                "--feed-url",
                base.resolve("/feed/jdks.json").toString()));
        assertThat(stdout).contains("graalvm-jdk-25").contains("is available at");
    }

    @Test
    void missing_spec_is_a_usage_error() {
        // The required positional (Arity.ONE) is enforced by the arg parser,
        // which exits with its usage code before the command body runs.
        assertThat(run("jdk", "ensure")).isEqualTo(2);
    }

    // --- fixtures -----------------------------------------------------------

    /** Build + serve a feed whose entries each get their own downloadable archive. */
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

    private record EntrySpec(
            String vendor, String product, String sdkPrefix, int major, String version, boolean defaultForMajor) {
        String installFolder() {
            return sdkPrefix + "-" + version;
        }
    }

    /** A Temurin entry (jk's default vendor). */
    private static EntrySpec feedEntry(int major, String version, boolean defaultForMajor) {
        return new EntrySpec("Eclipse", "Temurin", "temurin", major, version, defaultForMajor);
    }

    /** An arbitrary-vendor entry, for asserting the Temurin bias. */
    private static EntrySpec vendorEntry(
            String vendor, String product, String sdkPrefix, int major, String version, boolean defaultForMajor) {
        return new EntrySpec(vendor, product, sdkPrefix, major, version, defaultForMajor);
    }

    private static String entryJson(EntrySpec s, long size, String sha256, String url) {
        return """
                {
                  "vendor": "VENDOR",
                  "product": "PRODUCT",
                  "default": DEFAULT,
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
                .replace("DEFAULT", Boolean.toString(s.defaultForMajor()))
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

    private static void makeJdkInstall(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
    }

    /** An installed Oracle GraalVM — its release file must classify as ORACLE_GRAALVM. */
    private static void makeGraalvmInstall(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(
                home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Oracle Corporation\"\n" + "GRAALVM_VERSION=\""
                        + version + "\"\n");
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
}
