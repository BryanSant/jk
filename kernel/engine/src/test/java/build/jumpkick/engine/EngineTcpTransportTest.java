// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.config.JkEngineConfig;
import build.jumpkick.engine.protocol.EngineProtocol;
import build.jumpkick.plugin.protocol.Ndjson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The loopback-TCP transport (Windows' lane), forced via -Djk.engine.transport=tcp so the auth
 * handshake is exercised off-Windows. Regression: engine→engine signalling (helloProbe /
 * drainDisplaced) once sent a raw token line where the server requires the {@code auth} envelope,
 * so same-version election and takeover drain silently failed on TCP.
 */
class EngineTcpTransportTest {

    private final List<Path> tempDirs = new ArrayList<>();

    @BeforeAll
    static void forceTcpTransport() {
        System.setProperty("jk.engine.transport", "tcp");
    }

    @AfterAll
    static void restoreTransport() {
        System.clearProperty("jk.engine.transport");
    }

    private Path shortTempDir() throws IOException {
        Path dir = Files.createTempDirectory("jkt-");
        tempDirs.add(dir);
        return dir;
    }

    @AfterEach
    void cleanupTempDirs() {
        build.jumpkick.worker.JvmOptions.resetSharedPlanForTests();
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

    private static void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within " + timeout);
            Thread.sleep(10);
        }
    }

    /** Hello over TCP the way any client must: auth envelope first, then the hello. */
    private static String tcpHelloVersion(Path socketFile) {
        try {
            int port = Integer.parseInt(Files.readString(socketFile).trim());
            String token = Files.readString(EnginePaths.tokenFor(socketFile)).trim();
            try (SocketChannel ch = SocketChannel.open(new java.net.InetSocketAddress(
                    java.net.InetAddress.getLoopbackAddress(), port))) {
                BufferedWriter w = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                w.write(EngineProtocol.auth(token));
                w.write('\n');
                w.write(EngineProtocol.hello("probe"));
                w.write('\n');
                w.flush();
                String ack = r.readLine();
                if (ack == null || !EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) return null;
                return Ndjson.str(ack, "version");
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Test
    void same_version_election_and_hello_work_over_tcp() throws Exception {
        Path state = shortTempDir();
        EnginePaths.Paths p = EnginePaths.resolve(state);

        EngineServer first = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
        Thread t = new Thread(() -> {
            try {
                first.run();
            } catch (IOException ignored) {
            }
        });
        t.start();
        try {
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
            assertThat(tcpHelloVersion(EnginePaths.activeSocket(p))).isEqualTo("1.0.0-test");

            // The second same-version instance must LOSE the election — which only happens when
            // its helloProbe authenticates correctly over TCP. Before the auth-envelope fix this
            // probe silently failed and the second instance won a fresh generation instead.
            EngineServer second = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
            assertThat(second.run())
                    .as("same-version spawn race loses the election over TCP")
                    .isFalse();
        } finally {
            first.close();
            t.join(10_000);
        }
    }
}
