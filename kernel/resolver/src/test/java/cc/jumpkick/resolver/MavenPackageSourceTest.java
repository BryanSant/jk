// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.resolver.pubgrub.Term;
import com.sun.net.httpserver.HttpServer;
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

class MavenPackageSourceTest {

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
    void versions_returns_highest_first(@TempDir Path tempDir) throws Exception {
        servePath("/com/foo/widget/maven-metadata.xml", """
                <metadata>
                  <groupId>com.foo</groupId>
                  <artifactId>widget</artifactId>
                  <versioning>
                    <versions>
                      <version>1.0</version>
                      <version>2.0</version>
                      <version>1.5</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        MavenPackageSource src = newSource(tempDir);
        assertThat(src.versions("com.foo:widget")).containsExactly("2.0", "1.5", "1.0");
    }

    @Test
    void dependencies_filters_to_compile_and_runtime(@TempDir Path tempDir) throws Exception {
        servePom("com.foo", "widget", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>a</artifactId><version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>b</artifactId><version>1.0</version>
                      <scope>runtime</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>c</artifactId><version>1.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>d</artifactId><version>1.0</version>
                      <optional>true</optional>
                    </dependency>
                  </dependencies>
                </project>
                """);
        MavenPackageSource src = newSource(tempDir);
        assertThat(src.dependencies("com.foo:widget", "1.0"))
                .extracting(Term::pkg)
                .containsExactlyInAnyOrder("com.foo:a", "com.foo:b");
    }

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
}
