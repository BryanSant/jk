// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import build.jumpkick.cache.Cas;
import build.jumpkick.http.Http;
import build.jumpkick.lock.Lockfile;
import build.jumpkick.model.Dependency;
import build.jumpkick.model.Feature;
import build.jumpkick.model.Features;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.Scope;
import build.jumpkick.model.VersionSelector;
import build.jumpkick.repo.MavenRepo;
import build.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BOM-driven resolution end-to-end through {@link LockOrchestrator}:
 *
 * <ul>
 *   <li>A platform BOM dep collected from {@code [dependencies.platform]} contributes constraints
 *       to the resolver.
 *   <li>Two BOMs with conflicting constraints on the same coord surface a diagnostic listing both
 *       BOM coords.
 *   <li>Coords pinned by a BOM get a {@code pinned-by} field in the resulting {@link
 *       Lockfile.Artifact}.
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
        serveJUnitDefaults();
    }

    /**
     * jk injects latest-stable JUnit into every project's TEST scope, so the mock repo must offer a
     * resolvable version of those coords for any lock.
     */
    private void serveJUnitDefaults() {
        serveLeaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        serveLeaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    private void serveLeaf(String group, String artifact, String version) {
        serveMetadata(
                "/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml",
                group,
                artifact,
                List.of(version));
        servePom(
                group,
                artifact,
                version,
                "<project><groupId>"
                        + group
                        + "</groupId><artifactId>"
                        + artifact
                        + "</artifactId><version>"
                        + version
                        + "</version></project>");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

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
        serveMetadata("/com/foo/widget/maven-metadata.xml", "com.foo", "widget", List.of("1.0", "2.0"));
        servePom("com.foo", "widget", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                </project>
                """);

        JkBuild project = jkBuildWithDeps(Map.of(
                Scope.PLATFORM, List.of(Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"))),
                Scope.MAIN,
                        List.of(
                                // No version on the main dep — let the BOM pin it.
                                new Dependency("com.foo:widget", VersionSelector.parseFloating("1.0")))));

        LockOrchestrator orchestrator = new LockOrchestrator(repoGroup(tempDir));
        Lockfile lock = orchestrator.lock(project, "test");

        Lockfile.Artifact widget = lock.artifacts().stream()
                .filter(p -> p.name().equals("com.foo:widget"))
                .findFirst()
                .orElseThrow();
        assertThat(widget.version()).isEqualTo("1.0");
        assertThat(widget.pinnedBy()).isEqualTo("org.example:the-bom:1.0");
    }

    @Test
    void platform_managed_versionless_root_resolves_through_the_bom(@TempDir Path tempDir) throws Exception {
        // The Spring Boot flow: import spring-boot-dependencies, declare starters with
        // NO version at all (spring-boot plan §3.1) — the BOM supplies the pin.
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
        servePom("com.foo", "widget", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                </project>
                """);

        JkBuild project = jkBuildWithDeps(Map.of(
                Scope.PLATFORM,
                        List.of(Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"))),
                Scope.MAIN, List.of(Dependency.platformManaged("widget", "com.foo:widget"))));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");
        Lockfile.Artifact widget = lock.artifacts().stream()
                .filter(p -> p.name().equals("com.foo:widget"))
                .findFirst()
                .orElseThrow();
        assertThat(widget.version()).isEqualTo("1.0");
    }

    @Test
    void platform_managed_dep_with_no_bom_covering_it_errors_with_the_fix(@TempDir Path tempDir) {
        JkBuild project =
                jkBuildWithDeps(Map.of(Scope.MAIN, List.of(Dependency.platformManaged("widget", "com.foo:widget"))));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new LockOrchestrator(repoGroup(tempDir)).lock(project, "test"))
                .hasMessageContaining("no [platform-dependencies] BOM manages it");
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

        JkBuild project = jkBuildWithDeps(
                Map.of(Scope.PROCESSOR, List.of(new Dependency("com.foo:proc", VersionSelector.parseFloating("1.0")))));

        LockOrchestrator orchestrator = new LockOrchestrator(repoGroup(tempDir));
        Lockfile lock = orchestrator.lock(project, "test");

        Lockfile.Artifact proc = lock.artifacts().stream()
                .filter(p -> p.name().equals("com.foo:proc"))
                .findFirst()
                .orElseThrow(); // would be absent if PROCESSOR were dropped in resolution
        assertThat(proc.scopes()).contains(Scope.PROCESSOR);
    }

    @Test
    void optional_dep_is_withheld_until_a_feature_activates_it(@TempDir Path tempDir) throws Exception {
        serveMetadata("/com/foo/core/maven-metadata.xml", "com.foo", "core", List.of("1.0"));
        servePom("com.foo", "core", "1.0", leaf("core"));
        serveMetadata("/com/foo/extra/maven-metadata.xml", "com.foo", "extra", List.of("1.0"));
        servePom("com.foo", "extra", "1.0", leaf("extra"));

        // `extra` is optional; the `with-extra` feature (a default) names it.
        Dependency core = new Dependency("com.foo:core", VersionSelector.parseFloating("1.0"));
        Dependency extra = new Dependency("com.foo:extra", VersionSelector.parseFloating("1.0"))
                .withOptional(true); // library = "extra"
        Features features = new Features(
                Map.of("with-extra", new Feature("with-extra", List.of("extra"), List.of())), List.of("with-extra"));
        JkBuild project = jkBuildWithFeatures(
                new JkBuild.Dependencies(new EnumMap<>(Map.of(Scope.MAIN, List.of(core, extra)))), features);

        LockOrchestrator orchestrator = new LockOrchestrator(repoGroup(tempDir));

        // No features, no defaults → the optional dep stays out of the lock.
        Lockfile withoutFeature = orchestrator.lock(project, "test", List.of(), false);
        assertThat(modules(withoutFeature)).contains("com.foo:core").doesNotContain("com.foo:extra");

        // Defaults on → the feature pulls the optional dep in.
        Lockfile withFeature = orchestrator.lock(project, "test");
        assertThat(modules(withFeature)).contains("com.foo:core", "com.foo:extra");
    }

    @Test
    void feature_naming_a_non_optional_dep_is_an_error(@TempDir Path tempDir) {
        // `core` is a normal (non-optional) dep; a feature referencing it is a
        // config error — most likely a forgotten `optional = true`.
        Dependency core = new Dependency("com.foo:core", VersionSelector.parseFloating("1.0"));
        Features features = new Features(Map.of("x", new Feature("x", List.of("core"), List.of())), List.of("x"));
        JkBuild project = jkBuildWithFeatures(
                new JkBuild.Dependencies(new EnumMap<>(Map.of(Scope.MAIN, List.of(core)))), features);

        LockOrchestrator orchestrator = new LockOrchestrator(repoGroup(tempDir));
        assertThatThrownBy(() -> orchestrator.lock(project, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a declared optional dependency");
    }

    private static JkBuild jkBuildWithFeatures(JkBuild.Dependencies deps, Features features) {
        return JkBuild.builder(new JkBuild.Project("com.example", "test", "0.1.0", 25))
                .dependencies(deps)
                .features(features)
                .build();
    }

    private static List<String> modules(Lockfile lock) {
        return lock.artifacts().stream().map(Lockfile.Artifact::name).toList();
    }

    @Test
    void empty_test_scope_defaults_to_latest_stable_junit(@TempDir Path tempDir) throws Exception {
        // No dependencies at all — jk still defaults the test framework.
        JkBuild project = jkBuildWithDeps(Map.of());
        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");

        Lockfile.Artifact jupiter = lock.artifacts().stream()
                .filter(p -> p.name().equals("org.junit.jupiter:junit-jupiter"))
                .findFirst()
                .orElseThrow();
        assertThat(jupiter.version()).isEqualTo("6.1.0"); // latest stable the repo offers
        assertThat(jupiter.scopes()).contains(Scope.TEST);
        assertThat(lock.artifacts()).anyMatch(p -> p.name().equals("org.junit.platform:junit-platform-launcher"));
    }

    @Test
    void user_declared_junit_version_wins_over_default(@TempDir Path tempDir) throws Exception {
        // The user pins an older JUnit; the default must not override it.
        serveMetadata(
                "/org/junit/jupiter/junit-jupiter/maven-metadata.xml",
                "org.junit.jupiter",
                "junit-jupiter",
                List.of("5.10.0", "6.1.0"));
        servePom(
                "org.junit.jupiter",
                "junit-jupiter",
                "5.10.0",
                "<project><groupId>org.junit.jupiter</groupId>"
                        + "<artifactId>junit-jupiter</artifactId><version>5.10.0</version></project>");

        JkBuild project = jkBuildWithDeps(Map.of(
                Scope.TEST,
                List.of(new Dependency("org.junit.jupiter:junit-jupiter", VersionSelector.parse("=5.10.0")))));
        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project, "test");

        Lockfile.Artifact jupiter = lock.artifacts().stream()
                .filter(p -> p.name().equals("org.junit.jupiter:junit-jupiter"))
                .findFirst()
                .orElseThrow();
        assertThat(jupiter.version()).isEqualTo("5.10.0");
    }

    private static String leaf(String artifact) {
        return "<project><groupId>com.foo</groupId><artifactId>"
                + artifact
                + "</artifactId><version>1.0</version></project>";
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
        return new JkBuild(new JkBuild.Project("com.example", "test", "0.1.0", 25), deps);
    }

    private void servePath(String path, String body) {
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private void servePom(String group, String artifact, String version, String body) {
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
        servePath(path, body);
    }

    private void serveMetadata(String path, String group, String artifact, List<String> versions) {
        StringBuilder body = new StringBuilder();
        body.append("<metadata><groupId>")
                .append(group)
                .append("</groupId>")
                .append("<artifactId>")
                .append(artifact)
                .append("</artifactId>")
                .append("<versioning><versions>");
        for (String v : versions) body.append("<version>").append(v).append("</version>");
        body.append("</versions></versioning></metadata>");
        servePath(path, body.toString());
    }
}
