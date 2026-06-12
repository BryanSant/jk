// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BOM-driven resolution end-to-end through {@link LockOrchestrator}:
 *
 * <ul>
 *   <li>A platform BOM dep collected from {@code [dependencies.platform]}
 *       contributes constraints to the resolver.</li>
 *   <li>Two BOMs with conflicting constraints on the same coord surface a
 *       diagnostic listing both BOM coords.</li>
 *   <li>Coords pinned by a BOM get a {@code pinned-by} field in the
 *       resulting {@link Lockfile.Artifact}.</li>
 * </ul>
 */
class LockOrchestratorBomTest {

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
    void conflicting_platform_boms_surface_diagnostic(@TempDir Path tempDir) throws Exception {
        // Two BOMs that both constrain `com.foo:widget` to different versions.
        servePom("org.example", "bom-a", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>bom-a</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.foo</groupId>
                        <artifactId>widget</artifactId>
                        <version>1.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        servePom("org.example", "bom-b", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>bom-b</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.foo</groupId>
                        <artifactId>widget</artifactId>
                        <version>2.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        JkBuild project = jkBuildWithPlatformDeps(
                Dependency.of("bom-a", "org.example:bom-a", VersionSelector.parse("=1.0")),
                Dependency.of("bom-b", "org.example:bom-b", VersionSelector.parse("=1.0")));

        LockOrchestrator orchestrator = new LockOrchestrator(repoGroup(tempDir));
        assertThatThrownBy(() -> orchestrator.lock(project, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.foo:widget")
                .hasMessageContaining("bom-a")
                .hasMessageContaining("bom-b")
                .hasMessageContaining("1.0")
                .hasMessageContaining("2.0");
    }

    @Test
    void platform_bom_pins_lockfile_package(@TempDir Path tempDir) throws Exception {
        servePom("org.example", "the-bom", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>the-bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.foo</groupId>
                        <artifactId>widget</artifactId>
                        <version>1.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        // widget metadata advertises higher versions, but BOM pins 1.0.
        serveMetadata("/com/foo/widget/maven-metadata.xml",
                "com.foo", "widget", List.of("1.0", "2.0"));
        servePom("com.foo", "widget", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                </project>
                """);

        JkBuild project = jkBuildWithDeps(
                Map.of(
                        Scope.PLATFORM, List.of(
                                Dependency.of("the-bom", "org.example:the-bom",
                                        VersionSelector.parse("=1.0"))),
                        Scope.MAIN, List.of(
                                // No version on the main dep — let the BOM pin it.
                                new Dependency("com.foo:widget",
                                        VersionSelector.parseFloating("1.0")))));

        LockOrchestrator orchestrator = new LockOrchestrator(repoGroup(tempDir));
        Lockfile lock = orchestrator.lock(project, "test");

        Lockfile.Artifact widget = lock.artifacts().stream()
                .filter(p -> p.name().equals("com.foo:widget"))
                .findFirst().orElseThrow();
        assertThat(widget.version()).isEqualTo("1.0");
        assertThat(widget.pinnedBy()).isEqualTo("org.example:the-bom:1.0");
    }

    @Test
    void processor_scope_dependency_is_resolved_and_tagged_processor(@TempDir Path tempDir) throws Exception {
        serveMetadata("/com/foo/proc/maven-metadata.xml", "com.foo", "proc", List.of("1.0"));
        servePom("com.foo", "proc", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>proc</artifactId>
                  <version>1.0</version>
                </project>
                """);

        JkBuild project = jkBuildWithDeps(Map.of(
                Scope.PROCESSOR, List.of(
                        new Dependency("com.foo:proc", VersionSelector.parseFloating("1.0")))));

        LockOrchestrator orchestrator = new LockOrchestrator(repoGroup(tempDir));
        Lockfile lock = orchestrator.lock(project, "test");

        Lockfile.Artifact proc = lock.artifacts().stream()
                .filter(p -> p.name().equals("com.foo:proc"))
                .findFirst().orElseThrow();   // would be absent if PROCESSOR were dropped in resolution
        assertThat(proc.scopes()).contains(Scope.PROCESSOR);
    }

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", base, new Http(), cas));
    }

    private static JkBuild jkBuildWithPlatformDeps(Dependency... platformDeps) {
        return jkBuildWithDeps(Map.of(Scope.PLATFORM, List.of(platformDeps)));
    }

    private static JkBuild jkBuildWithDeps(Map<Scope, List<Dependency>> byScope) {
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(byScope);
        JkBuild.Dependencies deps = new JkBuild.Dependencies(copy);
        return new JkBuild(
                new JkBuild.Project("com.example", "test", "0.1.0", 25),
                deps);
    }

    private void servePath(String path, String body) {
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private void servePom(String group, String artifact, String version, String body) {
        String path = "/" + group.replace('.', '/') + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + ".pom";
        servePath(path, body);
    }

    private void serveMetadata(String path, String group, String artifact, List<String> versions) {
        StringBuilder body = new StringBuilder();
        body.append("<metadata><groupId>").append(group).append("</groupId>")
            .append("<artifactId>").append(artifact).append("</artifactId>")
            .append("<versioning><versions>");
        for (String v : versions) body.append("<version>").append(v).append("</version>");
        body.append("</versions></versioning></metadata>");
        servePath(path, body.toString());
    }
}
