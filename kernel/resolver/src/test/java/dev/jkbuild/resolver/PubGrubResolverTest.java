// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.repo.EffectivePomBuilder;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for {@link PubGrubResolver}'s BOM-constraint hook. Other PubGrub semantics are
 * covered by the in-memory solver tests under {@code pubgrub/} — these focus on the {@code
 * bomConstraints} map being threaded through {@link MavenPackageSource#versions} and correctly
 * overriding transitive at-least preferences.
 */
class PubGrubResolverTest {

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
    void bom_constrained_coord_wins_over_transitive_at_least(@TempDir Path tempDir) throws Exception {
        // root → middle@1.0 → leaf >= 1.0 (transitive at-least, would normally
        //                                  pick highest available 2.0)
        // BOM pins leaf = 1.5
        // Expected: resolver returns leaf = 1.5 (BOM override of "highest wins").
        serveMetadata("/com/foo/middle/maven-metadata.xml", "com.foo", "middle", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0", "1.5", "2.0"));
        servePom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>middle</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));
        servePom("com.foo", "leaf", "1.5", emptyPom("com.foo", "leaf", "1.5"));
        servePom("com.foo", "leaf", "2.0", emptyPom("com.foo", "leaf", "2.0"));

        // Without the BOM, jk's at-least semantics would pick 2.0 (the
        // highest version that satisfies >= 1.0). With the BOM pinning to
        // 1.5, the resolver returns 1.5 — Gradle-style override.
        RepoGroup repos = repoGroup(tempDir);
        Map<String, String> bom = Map.of("com.foo:leaf", "1.5");
        PubGrubResolver resolver = new PubGrubResolver(repos, bom);

        Resolution result = resolver.resolve(List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKey("com.foo:leaf");
        assertThat(result.modules().get("com.foo:leaf").version()).isEqualTo("1.5");
    }

    @Test
    void bom_pin_below_transitive_at_least_floor_is_unsatisfiable(@TempDir Path tempDir) throws Exception {
        // root → middle@1.0 → leaf >= 1.5
        // BOM pins leaf = 1.0 → can't satisfy >= 1.5. PubGrub diagnostic.
        serveMetadata("/com/foo/middle/maven-metadata.xml", "com.foo", "middle", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0", "1.5"));
        servePom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>middle</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.5</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));
        servePom("com.foo", "leaf", "1.5", emptyPom("com.foo", "leaf", "1.5"));

        RepoGroup repos = repoGroup(tempDir);
        Map<String, String> bom = Map.of("com.foo:leaf", "1.0");
        PubGrubResolver resolver = new PubGrubResolver(repos, bom);

        assertThatThrownBy(() ->
                        resolver.resolve(List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0")))))
                .isInstanceOf(dev.jkbuild.resolver.pubgrub.UnsatisfiableException.class)
                .hasMessageContaining("com.foo:leaf");
    }

    @Test
    void coord_not_in_any_bom_uses_normal_resolution(@TempDir Path tempDir) throws Exception {
        // A direct dep with a caret selector should pick the highest
        // satisfying version. With no BOM constraint on `com.foo:other`,
        // resolution proceeds as usual.
        serveMetadata("/com/foo/other/maven-metadata.xml", "com.foo", "other", List.of("1.0", "1.5", "2.0"));
        servePom("com.foo", "other", "1.5", emptyPom("com.foo", "other", "1.5"));

        RepoGroup repos = repoGroup(tempDir);
        // BOM constrains a *different* coord — should not interfere.
        Map<String, String> bom = Map.of("com.foo:unrelated", "0.1");
        PubGrubResolver resolver = new PubGrubResolver(repos, bom);

        Resolution result =
                resolver.resolve(List.of(new Dependency("com.foo:other", VersionSelector.parseFloating("1.5"))));
        assertThat(result.modules().get("com.foo:other").version()).isEqualTo("1.5");
    }

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", base, new Http(), cas));
    }

    @SuppressWarnings("unused")
    private MavenPackageSource newSource(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        MavenRepo repo = new MavenRepo("local", base, new Http(), cas);
        return new MavenPackageSource(repo, new EffectivePomBuilder(repo));
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

    private static String emptyPom(String group, String artifact, String version) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
    }
}
