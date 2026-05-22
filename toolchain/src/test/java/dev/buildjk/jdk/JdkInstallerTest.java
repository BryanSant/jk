// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

import com.sun.net.httpserver.HttpServer;
import dev.buildjk.http.Http;
import dev.buildjk.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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
        byte[] archive = buildTarGz(tempDir, "jdk-21.0.5+11", Map.of(
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
        byte[] archive = buildTarGz(tempDir, "jdk", Map.of("bin/java", "#!/fake"));
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
    void second_install_is_idempotent(@TempDir Path tempDir) throws Exception {
        byte[] archive = buildTarGz(tempDir, "jdk", Map.of("bin/java", "x"));
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

    /**
     * Build a tar.gz on disk using the system {@code tar} so the test
     * fixture matches what foojay actually serves. Returns the archive
     * bytes.
     */
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
