// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import cc.jumpkick.cli.Jk;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the full pipeline: init -> add -> lock -> tree / why / sync. */
class ReadSideIntegrationTest {

    // These tests drive the real fetch pipeline against a mock Maven server; fetched
    // artifacts mirror into the Maven local repo. Point that at a throwaway dir (see
    // M2Dirs) so stub artifacts never overwrite the developer's real ~/.m2 — the
    // fixture reuses real coordinates (junit-jupiter et al).
    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

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
        DefaultTestDepsFixture.seed(served);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void full_pipeline_init_add_lock_tree_why_sync(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf-jar".getBytes(StandardCharsets.UTF_8));
        registerMetadata("com.foo", "root", "1.0");
        registerPom("com.foo", "root", "1.0", pom("com.foo", "root", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        registerJar("com.foo", "root", "1.0", "root-jar".getBytes(StandardCharsets.UTF_8));

        Path cache = tempDir.resolve("cache");

        run("new", tempDir.toString());
        run("add", "com.foo:root:1.0", "-C", tempDir.toString());
        run("lock", "-C", tempDir.toString(), "--repo-url", base.toString(), "--cache-dir", cache.toString());

        // jk tree — strip ANSI escapes so the GAV-formatted labels
        // line up as plain substrings the assertions can match
        // against. --color=never drops the foreground colors but
        // leaves text attributes (underline/bold) in place, hence
        // the regex below.
        String tree = stripAnsi(captureStdout(() -> run("tree", "-C", tempDir.toString())));
        assertThat(tree).contains("com.foo:root:1.0");
        assertThat(tree).contains("com.foo:leaf:1.0");

        // jk why
        String why = stripAnsi(captureStdout(() -> run("why", "com.foo:leaf", "-C", tempDir.toString())));
        assertThat(why).contains("com.foo:leaf:1.0 is pulled in by:");
        assertThat(why).contains("com.foo:root:1.0");

        // jk sync — second time with cache populated should report up-to-date.
        String sync = captureStdout(() -> run("sync", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(sync).contains("up-to-date");

        // jk sync on a fresh cache should fetch.
        Path freshCache = tempDir.resolve("fresh-cache");
        Files.createDirectories(freshCache);
        String resync =
                captureStdout(() -> run("sync", "-C", tempDir.toString(), "--cache-dir", freshCache.toString()));
        // root + leaf + the two defaulted JUnit coords.
        assertThat(resync).contains("4 fetched");
    }

    @Test
    void why_returns_1_for_unknown_module(@TempDir Path tempDir) throws IOException {
        // The queried module isn't in the (empty) lock — jk new no longer writes
        // one, so stand in the empty lock a first build would produce.
        run("new", tempDir.toString());
        ScaffoldTestSupport.writeEmptyLock(tempDir);
        int exit = run("why", "com.foo:bar", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void tree_without_lockfile_errors(@TempDir Path tempDir) throws IOException {
        // Write a jk.toml by hand so no jk.lock is created.
        Files.writeString(
                tempDir.resolve("jk.toml"), "[project]\ngroup = \"com.example\"\nname = \"a\"\nversion = \"0.1.0\"\n");
        int exit = run("tree", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void sync_creates_lockfile_when_missing(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        run("new", tempDir.toString());
        run("add", "com.foo:leaf:1.0", "-C", tempDir.toString());
        // Erase the empty lockfile that `jk init` stamps so we can verify
        // sync creates a fresh one (with the dep we just added).
        Path lockFile = tempDir.resolve("jk.lock");
        Files.deleteIfExists(lockFile);

        String out = captureStdout(() -> run(
                "sync",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--repo-url",
                base.toString()));

        assertThat(lockFile).exists();
        // sync auto-locks when jk.lock is missing, then reports its summary.
        assertThat(out.replaceAll("\\u001B\\[[;0-9]*m", "")).contains("Sync");
        // The package we added should show up in the freshly written lock.
        assertThat(Files.readString(lockFile)).contains("com.foo:leaf");
    }

    @Test
    void sync_accepts_offline_prepare_flag(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        run("new", tempDir.toString());
        run("add", "com.foo:leaf:1.0", "-C", tempDir.toString());
        run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                base.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());

        int exit = run(
                "sync",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("fresh").toString(),
                "--offline-prepare");
        assertThat(exit).isEqualTo(0);
    }

    // --- helpers -----------------------------------------------------------

    private static int run(String... args) {
        return Jk.execute(args);
    }

    /** Remove ANSI CSI escape sequences from {@code s}. */
    private static String stripAnsi(String s) {
        return s.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
    }

    private static String captureStdout(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private void registerPom(String group, String artifact, String version, String body) {
        String path = "/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version
                + ".pom";
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private void registerJar(String group, String artifact, String version, byte[] bytes) {
        String path = "/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version
                + ".jar";
        served.put(path, bytes);
    }

    private void registerMetadata(String group, String artifact, String... versions) {
        StringBuilder xml = new StringBuilder("<metadata><groupId>")
                .append(group)
                .append("</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><versioning><versions>");
        for (String v : versions) xml.append("<version>").append(v).append("</version>");
        xml.append("</versions></versioning></metadata>");
        served.put(
                "/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml",
                xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String pom(String group, String artifact, String version, String depBlock) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(group, artifact, version, depBlock);
    }
}
