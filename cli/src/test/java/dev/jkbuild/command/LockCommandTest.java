// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LockCommandTest {

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
        ActiveConfig.reset();
    }

    @Test
    void init_add_lock_full_pipeline(@TempDir Path tempDir) throws Exception {
        // Set up a tiny graph: root -> leaf.
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        byte[] leafJar = "leaf-jar-bytes".getBytes(StandardCharsets.UTF_8);
        registerJar("com.foo", "leaf", "1.0", leafJar);

        registerMetadata("com.foo", "root", "1.0");
        registerPom("com.foo", "root", "1.0", pom("com.foo", "root", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        byte[] rootJar = "root-jar-bytes".getBytes(StandardCharsets.UTF_8);
        registerJar("com.foo", "root", "1.0", rootJar);

        // Run the verbs against the test repo.
        int exit;
        exit = run("new", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        exit = run("add", "com.foo:root:1.0", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        exit = run("lock",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        // Inspect the lockfile.
        Lockfile lock = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(lock.packages()).hasSize(2);
        assertThat(lock.packages()).extracting(Lockfile.Package::name)
                .containsExactly("com.foo:leaf", "com.foo:root"); // writer sorts by name

        Lockfile.Package leaf = pkg(lock, "com.foo:leaf");
        Lockfile.Package root = pkg(lock, "com.foo:root");

        assertThat(leaf.version()).isEqualTo("1.0");
        assertThat(leaf.checksum()).isEqualTo("sha256:" + Hashing.sha256Hex(leafJar));
        assertThat(leaf.source()).startsWith("central+").endsWith("/");
        assertThat(leaf.deps()).isEmpty();

        assertThat(root.deps()).containsExactly("com.foo:leaf@1.0");
        assertThat(root.checksum()).isEqualTo("sha256:" + Hashing.sha256Hex(rootJar));
    }

    @Test
    void lock_without_build_jk_fails(@TempDir Path tempDir) {
        int exit = run("lock", "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void lock_with_no_dependencies_writes_empty_lockfile(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        int exit = run("lock",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(lock.packages()).isEmpty();
    }

    @Test
    void lock_from_member_dir_locks_member_only(@TempDir Path tempDir) throws Exception {
        // External graph served by the test repo: root -> leaf.
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        registerMetadata("com.foo", "root", "1.0");
        registerPom("com.foo", "root", "1.0", pom("com.foo", "root", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        registerJar("com.foo", "root", "1.0", "root".getBytes(StandardCharsets.UTF_8));

        // Workspace root + two members. `app` depends on its sibling `libb`
        // (must be filtered out, never fetched) and the external com.foo:root.
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "ws"
                version = "0.1.0"

                [workspace]
                members = ["app", "libb"]
                """);
        Path app = Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "app"
                version = "0.1.0"

                [dependencies.main]
                libb = { group = "com.acme", name = "libb", version = "0.1.0" }
                root = { group = "com.foo",  name = "root", version = "1.0" }
                """);
        Path libb = Files.createDirectories(tempDir.resolve("libb"));
        Files.writeString(libb.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "libb"
                version = "0.1.0"
                """);

        // Invoke from INSIDE the member directory — locks only that member.
        int exit = run("lock",
                "-C", app.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        // Each member has its own lock file alongside its jk.toml.
        Lockfile lock = LockfileReader.read(app.resolve("jk.lock"));

        // External coords are resolved; the workspace sibling is not locked.
        assertThat(lock.packages()).extracting(Lockfile.Package::name)
                .containsExactlyInAnyOrder("com.foo:root", "com.foo:leaf");
        assertThat(lock.packages()).extracting(Lockfile.Package::name)
                .doesNotContain("com.acme:libb");

        // Workspace root's own jk.lock was NOT created by this invocation.
        assertThat(Files.exists(tempDir.resolve("jk.lock"))).isFalse();
    }

    @Test
    void lock_from_workspace_root_cascades_to_members(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        registerMetadata("com.foo", "root", "1.0");
        registerPom("com.foo", "root", "1.0", pom("com.foo", "root", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        registerJar("com.foo", "root", "1.0", "root".getBytes(StandardCharsets.UTF_8));

        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "ws"
                version = "0.1.0"

                [workspace]
                members = ["app", "libb"]
                """);
        Path app = Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "app"
                version = "0.1.0"

                [dependencies.main]
                libb = { group = "com.acme", name = "libb", version = "0.1.0" }
                root = { group = "com.foo",  name = "root", version = "1.0" }
                """);
        Path libb = Files.createDirectories(tempDir.resolve("libb"));
        Files.writeString(libb.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "libb"
                version = "0.1.0"
                """);

        // Invoke from the workspace root — cascades to all members.
        int exit = run("lock",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        // Root gets its own lock (empty — no deps declared at root).
        assertThat(Files.exists(tempDir.resolve("jk.lock"))).isTrue();

        // app gets its own lock with external deps; sibling not included.
        Lockfile appLock = LockfileReader.read(app.resolve("jk.lock"));
        assertThat(appLock.packages()).extracting(Lockfile.Package::name)
                .containsExactlyInAnyOrder("com.foo:root", "com.foo:leaf");
        assertThat(appLock.packages()).extracting(Lockfile.Package::name)
                .doesNotContain("com.acme:libb");

        // libb gets its own lock (empty — no deps declared).
        assertThat(Files.exists(libb.resolve("jk.lock"))).isTrue();
    }

    @Test
    void offline_reuses_a_satisfiable_lock(@TempDir Path tempDir) throws Exception {
        registerRootLeafGraph();
        Path cache = tempDir.resolve("cache");

        run("new", tempDir.toString());
        run("add", "com.foo:root:1.0", "-C", tempDir.toString());
        assertThat(run("lock", "-C", tempDir.toString(),
                "--repo-url", base.toString(), "--cache-dir", cache.toString())).isEqualTo(0);

        // Stop the server so any network attempt would fail; offline must not need it.
        server.stop(0);
        int exit = run("lock", "--offline", "-C", tempDir.toString(),
                "--cache-dir", cache.toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(lock.packages()).extracting(Lockfile.Package::name)
                .containsExactly("com.foo:leaf", "com.foo:root");
    }

    @Test
    void offline_hard_fails_when_declared_dep_missing_from_lock(@TempDir Path tempDir) throws Exception {
        // jk.toml declares com.foo:root, but the lockfile has no such package.
        writeProjectWithRootDep(tempDir);
        LockfileWriter.write(
                new Lockfile(Lockfile.CURRENT_VERSION, "jk test",
                        Lockfile.RESOLUTION_ALGORITHM, List.of()),
                tempDir.resolve("jk.lock"));

        int exit = run("lock", "--offline", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(6);
    }

    @Test
    void offline_hard_fails_when_locked_blob_not_cached(@TempDir Path tempDir) throws Exception {
        writeProjectWithRootDep(tempDir);
        // Lock references a checksum whose blob is absent from the (empty) cache.
        Lockfile.Package root = new Lockfile.Package(
                "com.foo:root", "1.0", "central+" + base, "sha256:" + "00".repeat(32),
                null, List.of(), List.of(), null);
        LockfileWriter.write(
                new Lockfile(Lockfile.CURRENT_VERSION, "jk test",
                        Lockfile.RESOLUTION_ALGORITHM, List.of(root)),
                tempDir.resolve("jk.lock"));

        int exit = run("lock", "--offline", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(6);
    }

    @Test
    void offline_solves_from_journal_when_no_lock_exists(@TempDir Path tempDir) throws Exception {
        registerRootLeafGraph();
        Path cache = tempDir.resolve("cache");

        // Warm the shared cache + journal with an online lock in one project.
        Path online = Files.createDirectories(tempDir.resolve("online"));
        run("new", online.toString());
        run("add", "com.foo:root:1.0", "-C", online.toString());
        assertThat(run("lock", "-C", online.toString(),
                "--repo-url", base.toString(), "--cache-dir", cache.toString())).isEqualTo(0);

        // Fresh project, no lockfile, offline — must resolve from the journal.
        Path fresh = Files.createDirectories(tempDir.resolve("fresh"));
        writeProjectWithRootDep(fresh);
        server.stop(0);
        int exit = run("lock", "--offline", "-C", fresh.toString(),
                "--cache-dir", cache.toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(fresh.resolve("jk.lock"));
        assertThat(lock.packages()).extracting(Lockfile.Package::name)
                .containsExactlyInAnyOrder("com.foo:root", "com.foo:leaf");
    }

    @Test
    void kotlin_project_lock_pins_floating_compiler_version(@TempDir Path tempDir) throws Exception {
        // 2.4.0-RC2 is higher than 2.3.21 and also in range, but a floating
        // selector must skip the pre-release and pin the highest stable.
        registerMetadata("org.jetbrains.kotlin", "kotlin-compiler-embeddable",
                "2.0.21", "2.3.0", "2.3.21", "2.4.0-RC2", "3.0.0");
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "app"
                version = "0.1.0"
                kotlin = "^2.3.0"
                """);

        int exit = run("lock", "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        // ^2.3.0 → >=2.3.0, <3.0.0; highest *stable* match is 2.3.21 (not 2.4.0-RC2).
        Lockfile lock = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(lock.kotlin()).isEqualTo("2.3.21");
    }

    @Test
    void kotlin_exact_pin_locks_without_metadata(@TempDir Path tempDir) throws Exception {
        // No kotlin-compiler-embeddable metadata registered — an exact pin
        // must short-circuit and lock without hitting the repo.
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "app"
                version = "0.1.0"
                kotlin = "=2.1.0"
                """);

        int exit = run("lock", "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(lock.kotlin()).isEqualTo("2.1.0");
    }

    @Test
    void java_project_lock_has_no_kotlin_version(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        int exit = run("lock", "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(LockfileReader.read(tempDir.resolve("jk.lock")).kotlin()).isNull();
    }

    // --- helpers -----------------------------------------------------------

    private static int run(String... args) {
        return Jk.execute(args);
    }

    /** Register a root -> leaf graph (metadata + pom + jar for each) on the test repo. */
    private void registerRootLeafGraph() {
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
    }

    private static void writeProjectWithRootDep(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "app"
                version = "0.1.0"

                [dependencies.main]
                root = { group = "com.foo", name = "root", version = "1.0" }
                """);
    }

    private static Lockfile.Package pkg(Lockfile lock, String module) {
        return lock.packages().stream()
                .filter(p -> p.name().equals(module))
                .findFirst().orElseThrow();
    }

    private void registerPom(String group, String artifact, String version, String body) {
        String path = "/" + group.replace('.', '/') + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + ".pom";
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private void registerJar(String group, String artifact, String version, byte[] bytes) {
        String path = "/" + group.replace('.', '/') + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + ".jar";
        served.put(path, bytes);
    }

    private void registerMetadata(String group, String artifact, String... versions) {
        StringBuilder xml = new StringBuilder("<metadata><groupId>").append(group)
                .append("</groupId><artifactId>").append(artifact)
                .append("</artifactId><versioning><versions>");
        for (String v : versions) xml.append("<version>").append(v).append("</version>");
        xml.append("</versions></versioning></metadata>");
        served.put("/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml",
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
