// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectivePomBuilderTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> poms = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Single catch-all handler that serves any registered POM and 404s otherwise.
        server.createContext("/", exchange -> {
            byte[] body = poms.get(exchange.getRequestURI().getPath());
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
    void inherits_group_and_version_from_parent(@TempDir Path tempDir) throws Exception {
        registerPom("org.example", "parent", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                </project>
                """);
        registerPom("org.example", "child", "1.0", """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """);

        EffectivePomBuilder builder = newBuilder(tempDir);
        EffectivePom pom = builder.build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.groupId()).isEqualTo("org.example");
        assertThat(pom.version()).isEqualTo("1.0");
    }

    @Test
    void merges_properties_with_child_winning(@TempDir Path tempDir) throws Exception {
        registerPom("org.example", "parent", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <properties>
                    <spring.version>6.0.0</spring.version>
                    <jackson.version>2.18.0</jackson.version>
                  </properties>
                </project>
                """);
        registerPom("org.example", "child", "1.0", """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                  <properties>
                    <spring.version>6.1.0</spring.version>
                  </properties>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.properties()).containsEntry("spring.version", "6.1.0");
        assertThat(pom.properties()).containsEntry("jackson.version", "2.18.0");
    }

    @Test
    void fills_dep_version_from_dependency_management(@TempDir Path tempDir) throws Exception {
        registerPom("org.example", "child", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>child</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                        <version>2.18.2</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.dependencies()).singleElement().satisfies(d -> assertThat(d.version())
                .isEqualTo("2.18.2"));
    }

    @Test
    void inherits_scope_from_dependency_management_not_just_version(@TempDir Path tempDir) throws Exception {
        // A dep declared with neither version NOR scope must inherit both from
        // dependencyManagement — Maven semantics. This is what keeps a
        // test-scoped managed dep (e.g. google-java-format's guava-testlib) off
        // the compile classpath instead of leaking as the default compile scope.
        registerPom("org.example", "child", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>child</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava-testlib</artifactId>
                        <version>32.1.3-jre</version>
                        <scope>test</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava-testlib</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.dependencies()).singleElement().satisfies(d -> {
            assertThat(d.version()).isEqualTo("32.1.3-jre");
            assertThat(d.scope()).isEqualTo("test"); // not null/compile
        });
    }

    @Test
    void expands_bom_import_into_managed_deps(@TempDir Path tempDir) throws Exception {
        registerPom("com.fasterxml.jackson", "jackson-bom", "2.18.2", """
                <project>
                  <groupId>com.fasterxml.jackson</groupId>
                  <artifactId>jackson-bom</artifactId>
                  <version>2.18.2</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                        <version>2.18.2</version>
                      </dependency>
                      <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-core</artifactId>
                        <version>2.18.2</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        registerPom("org.example", "child", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>child</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.fasterxml.jackson</groupId>
                        <artifactId>jackson-bom</artifactId>
                        <version>2.18.2</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.managedDependencies())
                .extracting(Pom.Dep::module)
                .containsExactlyInAnyOrder(
                        "com.fasterxml.jackson.core:jackson-databind", "com.fasterxml.jackson.core:jackson-core");
    }

    @Test
    void substitutes_chained_property_refs_across_parent(@TempDir Path tempDir) throws Exception {
        registerPom("org.example", "parent", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <properties>
                    <jdk>${java.target}</jdk>
                    <java.target>21</java.target>
                  </properties>
                </project>
                """);
        registerPom("org.example", "child", "1.0", """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.properties()).containsEntry("jdk", "${java.target}");
        // Substitution applies to dep fields, not the properties map itself —
        // the resolver only ever reads substituted dep coordinates.
    }

    @Test
    void later_dependency_management_entry_overrides_earlier(@TempDir Path tempDir) throws Exception {
        // The child first declares a local managed version (1.0), then
        // imports a BOM that constrains the same coord to 2.0. Per
        // EffectivePomBuilder's "later wins on collision" rule, the merged
        // managed list should carry 2.0.
        registerPom("org.example", "the-bom", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>the-bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>widget</artifactId>
                        <version>2.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        registerPom("org.example", "child", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>child</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>widget</artifactId>
                        <version>1.0</version>
                      </dependency>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>the-bom</artifactId>
                        <version>1.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.managedDependencies())
                .filteredOn(d -> d.module().equals("org.example:widget"))
                .singleElement()
                .satisfies(d -> assertThat(d.version()).isEqualTo("2.0"));
    }

    @Test
    void detects_parent_cycle(@TempDir Path tempDir) {
        registerPom("org.example", "a", "1.0", """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>b</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>a</artifactId>
                </project>
                """);
        registerPom("org.example", "b", "1.0", """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>a</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>b</artifactId>
                </project>
                """);

        assertThatThrownBy(() -> newBuilder(tempDir).build(Coordinate.of("org.example", "a", "1.0")))
                .isInstanceOf(PomParseException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void test_jar_variant_does_not_shadow_the_real_dependency(@TempDir Path tempDir) throws Exception {
        // The logback shape: the parent manages logback-core twice (jar + test-jar), and the
        // child depends on both (plain compile dep + test-scoped test-jar). Maven's dependency
        // identity is group:artifact:type:classifier — deduping on module alone let the
        // test-jar rows overwrite the real ones and logback-core vanished from every classpath.
        registerPom("org.example", "parent", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>core</artifactId>
                        <version>${project.version}</version>
                      </dependency>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>core</artifactId>
                        <version>${project.version}</version>
                        <type>test-jar</type>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        registerPom("org.example", "classic", "1.0", """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>classic</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>core</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>core</artifactId>
                      <type>test-jar</type>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "classic", "1.0"));
        // Both variants survive as distinct deps; the plain one keeps its managed jar version
        // and stays scopeless (compile), untouched by the test-jar row.
        assertThat(pom.dependencies())
                .filteredOn(d -> d.module().equals("org.example:core"))
                .hasSize(2);
        assertThat(pom.dependencies())
                .filteredOn(d -> d.module().equals("org.example:core")
                        && (d.type() == null || d.type().isBlank() || d.type().equals("jar")))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.version()).isEqualTo("1.0");
                    assertThat(d.scope()).isNullOrEmpty();
                });
    }

    // --- helpers -----------------------------------------------------------

    private EffectivePomBuilder newBuilder(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return new EffectivePomBuilder(new MavenRepo("local", base, new Http(), cas));
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
        poms.put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
