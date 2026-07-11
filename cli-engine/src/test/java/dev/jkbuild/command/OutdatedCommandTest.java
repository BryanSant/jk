// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cli.Jk;
import dev.jkbuild.lock.LockfileReader;
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

/**
 * Drives {@code jk outdated} against a mock Maven server (the version index only — outdated never
 * fetches artifacts) via the test-only in-process seam. Assertions mostly read the {@code --output
 * json} form so they don't depend on the human table's exact glyphs/spacing. Git tag/tip
 * enumeration is covered by the git-client and engine unit tests (no forked worker here).
 */
class OutdatedCommandTest {

    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> served = new HashMap<>();
    private final PrintStream originalOut = System.out;

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
        DefaultTestDepsFixture.seed(served); // implicit junit-platform-launcher, for the lock-based tests
    }

    @AfterEach
    void stop() {
        System.setOut(originalOut);
        server.stop(0);
        dev.jkbuild.config.SessionContext.reset();
        LockfileReader.clearCache();
    }

    @Test
    void reports_compatible_at_selector_ceiling_and_latest_beyond(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0", "1.1", "2.0");
        registerPom("com.foo", "leaf", "1.1", pom("com.foo", "leaf", "1.1"));
        registerJar("com.foo", "leaf", "1.1", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");

        writeProject(tempDir, "leaf = { group = \"com.foo\", name = \"leaf\", version = \"^1.0\" }");
        assertThat(lock(tempDir, cache)).isEqualTo(0);

        String json = json(tempDir, cache);
        assertThat(json).contains("\"dependency\":\"com.foo:leaf\"");
        assertThat(json).contains("\"current\":\"1.1\"");
        assertThat(json).contains("\"compatible\":\"1.1\"");
        assertThat(json).contains("\"latest\":\"2.0\"");
    }

    @Test
    void exclude_up_to_date_hides_current_but_keeps_behind(@TempDir Path tempDir) throws Exception {
        // upToDate: only 1.0 exists.  behind: 1.0 pinned but 2.0 exists.
        registerMetadata("com.foo", "upToDate", "1.0");
        registerPom("com.foo", "upToDate", "1.0", pom("com.foo", "upToDate", "1.0"));
        registerJar("com.foo", "upToDate", "1.0", "a".getBytes(StandardCharsets.UTF_8));
        registerMetadata("com.foo", "behind", "1.0", "2.0");
        registerPom("com.foo", "behind", "1.0", pom("com.foo", "behind", "1.0"));
        registerJar("com.foo", "behind", "1.0", "b".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");

        writeProject(
                tempDir,
                "upToDate = { group = \"com.foo\", name = \"upToDate\", version = \"=1.0\" }\n"
                        + "        behind = { group = \"com.foo\", name = \"behind\", version = \"=1.0\" }");
        assertThat(lock(tempDir, cache)).isEqualTo(0);

        String all = json(tempDir, cache);
        assertThat(all).contains("com.foo:upToDate").contains("com.foo:behind");

        String filtered = jsonArgs(tempDir, cache, "--exclude-up-to-date");
        assertThat(filtered).contains("com.foo:behind");
        assertThat(filtered).doesNotContain("com.foo:upToDate");
    }

    @Test
    void show_tip_adds_column_with_prerelease(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0", "1.1", "2.0-alpha1");
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo\", name = \"leaf\", version = \"^1.0\" }");
        // No lock needed: latest/tip come from metadata regardless of current.

        String plain = table(tempDir, cache); // no --show-tip
        assertThat(plain).doesNotContain("Tip");

        String withTip = table(tempDir, cache, "--show-tip");
        assertThat(withTip).contains("Tip");
        assertThat(withTip).contains("2.0-alpha1"); // non-stable frontier ahead of latest stable 1.1
    }

    @Test
    void substitutes_short_catalog_name_in_json(@TempDir Path tempDir) throws Exception {
        // A coordinate that maps to a bundled short name.
        registerMetadata("com.fasterxml.jackson.core", "jackson-databind", "2.18.0");
        Path cache = tempDir.resolve("cache");
        writeProject(
                tempDir,
                "jackson = { group = \"com.fasterxml.jackson.core\", name = \"jackson-databind\", version = \"^2.0\" }");

        String json = json(tempDir, cache);
        assertThat(json).contains("\"dependency\":\"com.fasterxml.jackson.core:jackson-databind\"");
        assertThat(json).contains("\"display\":\"jackson2-databind\"");
    }

    @Test
    void workspace_cascade_tags_each_row_with_its_module(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0", "2.0");
        registerMetadata("com.foo", "core", "3.0", "3.1");
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["app", "lib"]
                """);
        Path app = Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "app"
                version = "0.1.0"

                [dependencies]
                leaf = { group = "com.foo", name = "leaf", version = "^1.0" }
                """);
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "lib"
                version = "0.1.0"

                [dependencies]
                core = { group = "com.foo", name = "core", version = "^3.0" }
                """);

        String json = json(tempDir, tempDir.resolve("cache"));
        assertThat(json).contains("\"module\":\"com.acme:app\"").contains("\"dependency\":\"com.foo:leaf\"");
        assertThat(json).contains("\"module\":\"com.acme:lib\"").contains("\"dependency\":\"com.foo:core\"");

        // The Module column shows in the human table for a workspace.
        assertThat(table(tempDir, tempDir.resolve("cache"))).contains("Module");
    }

    @Test
    void human_table_lists_dependency_and_versions(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0", "1.1", "2.0");
        registerPom("com.foo", "leaf", "1.1", pom("com.foo", "leaf", "1.1"));
        registerJar("com.foo", "leaf", "1.1", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo\", name = \"leaf\", version = \"^1.0\" }");
        assertThat(lock(tempDir, cache)).isEqualTo(0);

        String out = table(tempDir, cache);
        assertThat(out).contains("Dependency", "Compatible", "Latest");
        assertThat(out).contains("com.foo:leaf");
        assertThat(out).contains("2.0");
    }

    @Test
    void without_jk_toml_fails_with_config_exit(@TempDir Path tempDir) {
        int exit = run(
                "outdated", "-C", tempDir.toString(), "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(2);
    }

    // --- helpers -----------------------------------------------------------

    private int lock(Path dir, Path cache) {
        return run("lock", "-C", dir.toString(), "--repo-url", base.toString(), "--cache-dir", cache.toString());
    }

    private String json(Path dir, Path cache) {
        return jsonArgs(dir, cache);
    }

    private String jsonArgs(Path dir, Path cache, String... extra) {
        return capture(dir, cache, concat(new String[] {"--output", "json"}, extra));
    }

    private String table(Path dir, Path cache, String... extra) {
        return capture(dir, cache, extra);
    }

    private String capture(Path dir, Path cache, String[] extra) {
        String[] base = {
            "outdated", "-C", dir.toString(), "--repo-url", this.base.toString(), "--cache-dir", cache.toString()
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run(concat(base, extra));
        } finally {
            System.setOut(originalOut);
        }
        assertThat(exit).isEqualTo(0);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static void writeProject(Path dir, String depLines) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "app"
                version = "0.1.0"

                [dependencies]
                %s
                """.formatted(depLines));
    }

    private static int run(String... args) {
        return Jk.execute(args);
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

    private void registerPom(String group, String artifact, String version, String body) {
        served.put(coordPath(group, artifact, version, "pom"), body.getBytes(StandardCharsets.UTF_8));
    }

    private void registerJar(String group, String artifact, String version, byte[] bytes) {
        served.put(coordPath(group, artifact, version, "jar"), bytes);
    }

    private static String coordPath(String group, String artifact, String version, String ext) {
        return "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + "." + ext;
    }

    private static String pom(String group, String artifact, String version) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies></dependencies>
                </project>
                """.formatted(group, artifact, version);
    }
}
