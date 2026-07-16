// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Ndjson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Downward delegation (engine-versioning-plan §3): pin parsing, ordering, and the intake gate —
 * an OLDER pin is refused with the not-materialized message when the version is absent (the
 * full child exec is covered by the wrapper/e2e flow once release jars exist side-by-side);
 * a NEWER pin is refused with the upgrade-shaped error, never supervised.
 */
class EngineDelegateTest {

    private final List<Path> tempDirs = new ArrayList<>();

    private Path shortTempDir() throws IOException {
        Path dir = Files.createTempDirectory("jkg-");
        tempDirs.add(dir);
        return dir;
    }

    @AfterEach
    void cleanupTempDirs() {
        cc.jumpkick.engine.plugin.JvmOptions.resetSharedPlanForTests();
        for (Path dir : tempDirs) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException | java.io.UncheckedIOException ignored) {
            }
        }
    }

    private static void writeLockWithPin(Path dir, String version) throws IOException {
        Files.writeString(dir.resolve("jk.lock"), """
                version = 1
                generated-by = "jk %s"
                resolution-algorithm = "pubgrub-v1"
                jk = { version = "%s", sha256 = "" }
                """.formatted(version, version));
    }

    @Test
    void pin_parsing_and_ordering() throws Exception {
        Path dir = shortTempDir();
        assertThat(EngineDelegate.pinnedVersionDiffering(dir, "1.0.0")).isNull(); // no lock

        writeLockWithPin(dir, "1.0.0");
        assertThat(EngineDelegate.pinnedVersionDiffering(dir, "1.0.0")).isNull(); // same version

        writeLockWithPin(dir, "0.9.0");
        assertThat(EngineDelegate.pinnedVersionDiffering(dir, "1.0.0")).isEqualTo("0.9.0");
        assertThat(EngineDelegate.pinIsNewer("0.9.0", "1.0.0")).isFalse();
        assertThat(EngineDelegate.pinIsNewer("2.0.0", "1.0.0")).isTrue();
    }

    @Test
    void newer_pin_is_refused_with_the_upgrade_error_and_older_unmaterialized_with_the_fetch_error() throws Exception {
        Path state = shortTempDir();
        EnginePaths.Paths p = EnginePaths.resolve(state);
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
        Thread t = new Thread(() -> {
            try {
                server.run();
            } catch (IOException ignored) {
            }
        });
        t.start();
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        Path newerProject = shortTempDir();
        writeLockWithPin(newerProject, "9.9.9");
        String err = requestError(EnginePaths.activeSocket(p), newerProject);
        assertThat(err).contains("pins jk 9.9.9").contains("self update");

        Path olderProject = shortTempDir();
        writeLockWithPin(olderProject, "0.0.1");
        String err2 = requestError(EnginePaths.activeSocket(p), olderProject);
        assertThat(err2).contains("jk 0.0.1").contains("not materialized");

        server.close();
        t.join(10_000);
    }

    /** Send a single-build request for {@code project}; return the first error message. */
    private static String requestError(Path socket, Path project) throws IOException {
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(socket));
            BufferedWriter w =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader r =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            w.write(EngineProtocol.singleBuildRequest(
                    project.toString(), project.resolve("cache").toString(), null, 1, null, true, false, false, false));
            w.write('\n');
            w.flush();
            String line;
            while ((line = r.readLine()) != null) {
                if (EngineProtocol.typeOf(line) != null && line.contains("\"message\"")) {
                    return Ndjson.str(line, "message");
                }
            }
            return "";
        }
    }

    private static void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within " + timeout);
            Thread.sleep(10);
        }
    }
}
