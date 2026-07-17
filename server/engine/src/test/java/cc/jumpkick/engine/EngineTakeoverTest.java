// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Jsonl;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Engine-versioning-plan §2: a newer engine takes over by atomically repointing the endpoint
 * file and gracefully draining the displaced generation — no kill, no lull-waiting. Also covers
 * the displacement watchdog (an engine whose endpoint stops naming it drains itself).
 */
class EngineTakeoverTest {

    private final List<Path> tempDirs = new ArrayList<>();

    private Path shortTempDir() throws IOException {
        Path dir = Files.createTempDirectory("jkt-");
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

    private static void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within " + timeout);
            Thread.sleep(10);
        }
    }

    private static String helloVersion(Path socket) {
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(socket));
            BufferedWriter w =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader r =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            w.write(EngineProtocol.hello("probe"));
            w.write('\n');
            w.flush();
            String ack = r.readLine();
            if (ack == null || !EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) return null;
            return Jsonl.str(ack, "version");
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * A -SNAPSHOT dev rebuild is the SAME version string with DIFFERENT content: the election
     * must treat a differing buildId as a skew (takeover), never as "already serving" — stale
     * dev engines once won these elections and kept serving old code. Empty buildIds fall back
     * to the version-string rule (release behavior, pinned by the same-version election test).
     */
    @Test
    void same_version_different_build_id_takes_over_instead_of_losing() throws Exception {
        Path state = shortTempDir();
        EnginePaths.Paths p = EnginePaths.resolve(state);

        EngineServer stale = new EngineServer(p, JkEngineConfig.DEFAULTS, null, "1.0.0-SNAPSHOT", "aaaaaaaaaaaa", null);
        CountDownLatch staleDone = new CountDownLatch(1);
        Thread staleT = new Thread(() -> {
            try {
                stale.run();
            } catch (IOException ignored) {
            } finally {
                staleDone.countDown();
            }
        });
        staleT.start();
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
        assertThat(helloVersion(EnginePaths.activeSocket(p))).isEqualTo("1.0.0-SNAPSHOT");

        // Rebuilt dev engine: same version, different content identity — must WIN (take over).
        EngineServer rebuilt =
                new EngineServer(p, JkEngineConfig.DEFAULTS, null, "1.0.0-SNAPSHOT", "bbbbbbbbbbbb", null);
        Thread rebuiltT = new Thread(() -> {
            try {
                rebuilt.run();
            } catch (IOException ignored) {
            }
        });
        rebuiltT.start();
        try {
            assertThat(staleDone.await(15, java.util.concurrent.TimeUnit.SECONDS))
                    .as("stale same-version engine is drained by the rebuilt one")
                    .isTrue();
            assertThat(helloVersion(EnginePaths.activeSocket(p))).isEqualTo("1.0.0-SNAPSHOT");
        } finally {
            rebuilt.close();
            rebuiltT.join(10_000);
        }
    }

    @Test
    void newer_engine_takes_over_and_the_displaced_one_drains() throws Exception {
        Path state = shortTempDir();
        EnginePaths.Paths p = EnginePaths.resolve(state);

        EngineServer old = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
        AtomicBoolean oldExited = new AtomicBoolean();
        CountDownLatch oldDone = new CountDownLatch(1);
        Thread oldT = new Thread(() -> {
            try {
                old.run();
            } catch (IOException ignored) {
            } finally {
                oldExited.set(true);
                oldDone.countDown();
            }
        });
        oldT.start();
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
        Path firstSocket = EnginePaths.activeSocket(p);
        assertThat(helloVersion(firstSocket)).isEqualTo("1.0.0-test");

        // A newer engine starts: it must claim a fresh generation, repoint the endpoint, and
        // drain the old engine — which, idle, exits promptly. Nothing is killed.
        EngineServer newer = new EngineServer(p, JkEngineConfig.DEFAULTS, "2.0.0-test", null);
        Thread newT = new Thread(() -> {
            try {
                newer.run();
            } catch (IOException ignored) {
            }
        });
        newT.start();

        waitUntil(Duration.ofSeconds(10), () -> "2.0.0-test".equals(helloVersion(EnginePaths.activeSocket(p))));
        assertThat(EnginePaths.activeSocket(p)).isNotEqualTo(firstSocket);
        assertThat(oldDone.await(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("displaced engine drains and exits at idle")
                .isTrue();
        assertThat(oldExited).isTrue();

        // The survivor still serves via the repointed endpoint.
        assertThat(helloVersion(EnginePaths.activeSocket(p))).isEqualTo("2.0.0-test");
        newer.close();
        newT.join(10_000);
    }

    @Test
    void displacement_watchdog_drains_an_engine_the_endpoint_no_longer_names(
            @org.junit.jupiter.api.io.TempDir Path unused) throws Exception {
        Path state = shortTempDir();
        EnginePaths.Paths p = EnginePaths.resolve(state);

        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                server.run();
            } catch (IOException ignored) {
            } finally {
                done.countDown();
            }
        });
        t.start();
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        // Simulate a takeover whose drain signal was lost: repoint the endpoint elsewhere.
        Files.writeString(EnginePaths.endpoint(p), p.key() + ".gen999.sock");

        // The watchdog (5s tick) notices and, idle, exits.
        assertThat(done.await(20, java.util.concurrent.TimeUnit.SECONDS))
                .as("watchdog self-drains a displaced engine")
                .isTrue();
    }
}
