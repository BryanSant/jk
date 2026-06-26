// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link MvnCommand} and {@link GradleCommand} end-to-end against a
 * local HTTP server serving a synthetic distribution. The "binary" inside the
 * zip is a tiny shell script that records its argv and env so we can verify
 * passthrough + env scrubbing without depending on a real Maven/Gradle.
 */
@DisabledOnOs(OS.WINDOWS) // launcher scripts are .sh-only.
class MvnGradleCommandTest {

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
    void mvn_passthrough_installs_and_forwards_args(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve(".mvn/wrapper"));
        Path argsLog = tempDir.resolve("argv.log");
        Path envLog = tempDir.resolve("env.log");

        served.put("/apache-maven-3.9.9-bin.zip", recordingZip("apache-maven-3.9.9", "mvn", argsLog, envLog));
        Files.writeString(
                projectDir.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=" + base.resolve("/apache-maven-3.9.9-bin.zip") + "\n");

        int exit = run(
                "mvn",
                "-C",
                projectDir.toString(),
                "--tools-dir",
                tempDir.resolve("tools").toString(),
                "--no-discover",
                "--jdks-dir",
                tempDir.resolve("jdks").toString(),
                "clean",
                "install",
                "-DskipTests=true",
                "-X");
        assertThat(exit).isEqualTo(0);

        assertThat(tempDir.resolve("tools/maven/3.9.9/bin/mvn")).exists();
        assertThat(Files.readString(argsLog).trim()).isEqualTo("clean install -DskipTests=true -X");

        String env = Files.readString(envLog);
        assertThat(env).doesNotContain("MAVEN_OPTS=");
        assertThat(env).doesNotContain("JAVA_TOOL_OPTIONS=");
    }

    @Test
    void gradle_passthrough_installs_and_forwards_args(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve("gradle/wrapper"));
        Path argsLog = tempDir.resolve("argv.log");
        Path envLog = tempDir.resolve("env.log");

        served.put("/gradle-9.5.1-bin.zip", recordingZip("gradle-9.5.1", "gradle", argsLog, envLog));
        Files.writeString(
                projectDir.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=" + base.resolve("/gradle-9.5.1-bin.zip") + "\n");

        int exit = run(
                "gradle",
                "-C",
                projectDir.toString(),
                "--tools-dir",
                tempDir.resolve("tools").toString(),
                "--no-discover",
                "--jdks-dir",
                tempDir.resolve("jdks").toString(),
                "build",
                "--no-daemon");
        assertThat(exit).isEqualTo(0);

        assertThat(tempDir.resolve("tools/gradle/9.5.1/bin/gradle")).exists();
        assertThat(Files.readString(argsLog).trim()).isEqualTo("build --no-daemon");
    }

    @Test
    void mvn_passthrough_does_not_install_twice(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve(".mvn/wrapper"));
        Path argsLog = tempDir.resolve("argv.log");
        Path envLog = tempDir.resolve("env.log");

        served.put("/apache-maven-3.9.9-bin.zip", recordingZip("apache-maven-3.9.9", "mvn", argsLog, envLog));
        Files.writeString(
                projectDir.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=" + base.resolve("/apache-maven-3.9.9-bin.zip") + "\n");

        run(
                "mvn",
                "-C",
                projectDir.toString(),
                "--tools-dir",
                tempDir.resolve("tools").toString(),
                "--no-discover",
                "--jdks-dir",
                tempDir.resolve("jdks").toString(),
                "first");
        long firstMtime = Files.getLastModifiedTime(tempDir.resolve("tools/maven/3.9.9/bin/mvn"))
                .toMillis();

        // Drop the served archive; second invocation must not need it.
        served.clear();
        int exit = run(
                "mvn",
                "-C",
                projectDir.toString(),
                "--tools-dir",
                tempDir.resolve("tools").toString(),
                "--no-discover",
                "--jdks-dir",
                tempDir.resolve("jdks").toString(),
                "second");
        assertThat(exit).isEqualTo(0);

        long secondMtime = Files.getLastModifiedTime(tempDir.resolve("tools/maven/3.9.9/bin/mvn"))
                .toMillis();
        assertThat(secondMtime).isEqualTo(firstMtime);
        assertThat(Files.readString(argsLog).trim()).isEqualTo("second");
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }

    /**
     * A zip carrying a single shell launcher that writes its argv to one file
     * and its env to another. Used as a synthetic mvn/gradle distribution.
     */
    private static byte[] recordingZip(String topDir, String binaryName, Path argsLog, Path envLog) throws IOException {
        String script = "#!/usr/bin/env bash\n"
                + "echo \"$@\" > " + shellQuote(argsLog.toString()) + "\n"
                + "env > " + shellQuote(envLog.toString()) + "\n"
                + "exit 0\n";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(topDir + "/bin/" + binaryName));
            zos.write(script.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
