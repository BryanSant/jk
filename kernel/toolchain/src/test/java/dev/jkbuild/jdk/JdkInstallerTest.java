// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.http.Http;
import dev.jkbuild.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkInstallerTest {

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
    void installs_tar_gz_archive(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz("jdk-21.0.5+11", Map.of(
                "bin/java", "#!/fake/java",
                "release", "JAVA_VERSION=21.0.5\n"));

        served.put("/jdk.tar.gz", archive);
        Path jdksRoot = tempDir.resolve("jdks");
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));

        JdkPackage pkg = new JdkPackage(
                "temurin", "21.0.5", "x64", "linux", "tar.gz",
                "OpenJDK21U.tar.gz",
                base.resolve("/jdk.tar.gz"),
                Hashing.sha256Hex(archive),
                archive.length);

        InstalledJdk installed = installer.install(pkg);
        assertThat(installed.identifier()).isEqualTo("21.0.5-tem-x64-linux");
        assertThat(installed.home().resolve("bin/java")).exists();
        assertThat(installed.home().resolve("release")).exists();
    }

    @Test
    void sha256_mismatch_aborts_install(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz("jdk", Map.of("bin/java", "#!/fake"));
        served.put("/jdk.tar.gz", archive);

        JdkInstaller installer = new JdkInstaller(new Http(),
                new JdkRegistry(tempDir.resolve("jdks")));
        JdkPackage pkg = new JdkPackage(
                "temurin", "21.0.5", "x64", "linux", "tar.gz",
                "OpenJDK21U.tar.gz",
                base.resolve("/jdk.tar.gz"),
                "deadbeef",
                archive.length);

        assertThatThrownBy(() -> installer.install(pkg))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sha256 mismatch");
    }

    @Test
    void appledouble_sidecars_are_skipped(@TempDir Path tempDir) throws Exception {
        // A macOS-built tarball can carry ._<name> sidecars next to real
        // entries. Installer must drop them so single-top-level flattening
        // still triggers and no junk lands in the JDK install directory.
        byte[] archive = buildTarGzRaw(new String[][] {
                {"jdk-21.0.5+11/",            null},
                {"._jdk-21.0.5+11",           "applesidecar"},
                {"jdk-21.0.5+11/bin/",        null},
                {"jdk-21.0.5+11/._bin",       "applesidecar"},
                {"jdk-21.0.5+11/bin/java",    "#!/fake/java"},
                {"jdk-21.0.5+11/bin/._java",  "applesidecar"},
                {"jdk-21.0.5+11/release",     "JAVA_VERSION=21.0.5\n"},
                {"jdk-21.0.5+11/._release",   "applesidecar"},
        });
        served.put("/jdk.tar.gz", archive);

        JdkInstaller installer = new JdkInstaller(new Http(),
                new JdkRegistry(tempDir.resolve("jdks")));
        JdkPackage pkg = new JdkPackage(
                "temurin", "21.0.5", "x64", "linux", "tar.gz",
                "OpenJDK21U.tar.gz",
                base.resolve("/jdk.tar.gz"),
                Hashing.sha256Hex(archive),
                archive.length);

        InstalledJdk installed = installer.install(pkg);
        assertThat(installed.home().resolve("bin/java")).exists();
        assertThat(installed.home().resolve("release")).exists();
        assertThat(installed.home().resolve("._bin")).doesNotExist();
        assertThat(installed.home().resolve("._release")).doesNotExist();
        assertThat(installed.home().resolve("bin/._java")).doesNotExist();
    }

    @Test
    void second_install_is_idempotent(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz("jdk", Map.of("bin/java", "x"));
        served.put("/jdk.tar.gz", archive);
        JdkInstaller installer = new JdkInstaller(new Http(),
                new JdkRegistry(tempDir.resolve("jdks")));
        JdkPackage pkg = new JdkPackage(
                "temurin", "21.0.5", "x64", "linux", "tar.gz",
                "OpenJDK21U.tar.gz",
                base.resolve("/jdk.tar.gz"),
                Hashing.sha256Hex(archive),
                archive.length);
        InstalledJdk first = installer.install(pkg);
        InstalledJdk second = installer.install(pkg);
        assertThat(second.home()).isEqualTo(first.home());
    }

    @Test
    void installs_jetbrains_catalog_entry(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz("jdk-21.0.5", Map.of(
                "bin/java", "#!/fake/java",
                "release", "JAVA_VERSION=21.0.5\n"));
        served.put("/jdk.tar.gz", archive);

        Path jdksRoot = tempDir.resolve("jdks");
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkCatalog.Entry entry = entry("linux", "x86_64", "",
                base.resolve("/jdk.tar.gz"), Hashing.sha256Hex(archive));

        InstalledJdk installed = installer.install(entry);
        assertThat(installed.identifier()).isEqualTo("temurin-21.0.5");
        assertThat(installed.home()).isEqualTo(jdksRoot.resolve("temurin-21.0.5"));
        assertThat(installed.home().resolve("bin/java")).exists();
    }

    @Test
    void macos_entry_resolves_home_through_contents_home(@TempDir Path tempDir) throws Exception {
        // Tarball layout: jdk-21.0.5.jdk/Contents/Home/...
        byte[] archive = buildTarGz("jdk-21.0.5.jdk", Map.of(
                "Contents/Home/bin/java", "#!/fake/java",
                "Contents/Home/release", "JAVA_VERSION=21.0.5\n"));
        served.put("/jdk.tar.gz", archive);

        Path jdksRoot = tempDir.resolve("jdks");
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkCatalog.Entry entry = entry("macOS", "aarch64", "Contents/Home",
                base.resolve("/jdk.tar.gz"), Hashing.sha256Hex(archive));

        InstalledJdk installed = installer.install(entry);
        assertThat(installed.home())
                .isEqualTo(jdksRoot.resolve("temurin-21.0.5").resolve("Contents").resolve("Home"));
        assertThat(installed.home().resolve("bin/java")).exists();
        assertThat(installed.home().resolve("release")).exists();
    }

    private static JdkCatalog.Entry entry(String os, String arch, String javaHomeSubpath,
                                          URI url, String sha256) {
        return new JdkCatalog.Entry(
                "Eclipse", "Temurin", "temurin-21", 21, "21.0.5",
                true, false,
                java.util.List.of("temurin-21.0.5", "temurin-21", "21.0.5", "21"),
                os, arch, "targz", url, sha256, 1024L,
                "temurin-21.0.5", javaHomeSubpath);
    }

    /**
     * Build a gzipped tar in-memory using Commons Compress — same library
     * the installer reads with — so the fixture matches what foojay serves
     * without depending on a system {@code tar} binary (or its
     * platform-specific quirks, e.g. macOS AppleDouble sidecars).
     */
    private static byte[] buildTarGz(String topLevelDir,
                                     Map<String, String> entries) throws IOException {
        String[][] raw = new String[entries.size() + 1][];
        raw[0] = new String[] {topLevelDir + "/", null};
        int i = 1;
        for (var e : entries.entrySet()) {
            raw[i++] = new String[] {topLevelDir + "/" + e.getKey(), e.getValue()};
        }
        return buildTarGzRaw(raw);
    }

    /**
     * Build a tar.gz with entries written verbatim — no implicit top-level
     * dir wrapping. Entry value {@code null} means a directory entry.
     */
    /**
     * Build a tar.gz using hand-rolled 512-byte TAR blocks — no external library.
     * {@code null} body = directory entry.
     */
    private static byte[] buildTarGzRaw(String[][] rawEntries) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (String[] e : rawEntries) {
            String name = e[0];
            byte[] data = e[1] != null ? e[1].getBytes(StandardCharsets.UTF_8) : null;
            boolean isDir = data == null;
            byte[] header = new byte[512];
            // name (0-99)
            byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 99));
            // mode (100-107)
            putOctal(header, 100, 8, isDir ? 0755 : 0644);
            // uid/gid (108-115, 116-123)
            putOctal(header, 108, 8, 0); putOctal(header, 116, 8, 0);
            // size (124-135)
            putOctal(header, 124, 12, data != null ? data.length : 0);
            // mtime (136-147)
            putOctal(header, 136, 12, 0);
            // type (156)
            header[156] = (byte)(isDir ? '5' : '0');
            // ustar magic (257-262)
            System.arraycopy("ustar ".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
            header[263] = ' '; header[264] = 0;
            // checksum (148-155): fill with spaces first, then compute
            java.util.Arrays.fill(header, 148, 156, (byte)' ');
            int sum = 0; for (byte b : header) sum += (b & 0xFF);
            putOctal(header, 148, 8, sum);
            raw.write(header);
            if (data != null && data.length > 0) {
                raw.write(data);
                int pad = 512 - (data.length % 512); if (pad < 512) raw.write(new byte[pad]);
            }
        }
        raw.write(new byte[1024]); // two zero blocks = end of archive
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(raw.toByteArray());
        }
        return bytes.toByteArray();
    }

    private static void putOctal(byte[] buf, int off, int len, long value) {
        String octal = String.format("%0" + (len - 1) + "o", value);
        byte[] oBytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(oBytes, 0, buf, off, Math.min(oBytes.length, len - 1));
        buf[off + len - 1] = 0;
    }
}
