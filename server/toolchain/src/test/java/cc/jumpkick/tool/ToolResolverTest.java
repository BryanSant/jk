// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolResolverTest {

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
    void resolves_a_single_artifact_and_reads_main_class(@TempDir Path tempDir) throws Exception {
        servePomAndJar("com.example", "widget-cli", "1.0.0", "com.example.Main");

        Cas cas = new Cas(tempDir.resolve("cas"));
        Files.createDirectories(tempDir.resolve("cas"));
        ToolResolver resolver = new ToolResolver(RepoGroup.of(new MavenRepo("central", base, new Http(), cas)));

        ToolEnv env = resolver.resolve(Coordinate.of("com.example", "widget-cli", "1.0.0"), "widget", null);

        assertThat(env.binName()).isEqualTo("widget");
        assertThat(env.mainClass()).isEqualTo("com.example.Main");
        assertThat(env.classpath()).hasSize(1);
        assertThat(env.classpath().getFirst()).exists();
    }

    @Test
    void main_class_override_wins_over_manifest(@TempDir Path tempDir) throws Exception {
        servePomAndJar("com.example", "widget-cli", "1.0.0", "com.example.Main");

        Cas cas = new Cas(tempDir.resolve("cas"));
        Files.createDirectories(tempDir.resolve("cas"));
        ToolResolver resolver = new ToolResolver(RepoGroup.of(new MavenRepo("central", base, new Http(), cas)));

        ToolEnv env =
                resolver.resolve(Coordinate.of("com.example", "widget-cli", "1.0.0"), "widget", "com.example.AltMain");

        assertThat(env.mainClass()).isEqualTo("com.example.AltMain");
    }

    @Test
    void missing_main_class_with_no_override_throws(@TempDir Path tempDir) throws Exception {
        servePomAndJar("com.example", "lib", "1.0.0", null);

        Cas cas = new Cas(tempDir.resolve("cas"));
        Files.createDirectories(tempDir.resolve("cas"));
        ToolResolver resolver = new ToolResolver(RepoGroup.of(new MavenRepo("central", base, new Http(), cas)));

        assertThatThrownBy(() -> resolver.resolve(Coordinate.of("com.example", "lib", "1.0.0"), "lib", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Main-Class");
    }

    @Test
    void transitive_deps_are_included_in_classpath(@TempDir Path tempDir) throws Exception {
        servePom("com.example", "widget-cli", "1.0.0", """
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>shared</artifactId>
                    <version>1.0.0</version>
                  </dependency>
                </dependencies>
                """);
        serveJar("com.example", "widget-cli", "1.0.0", "com.example.Main");
        servePom("com.example", "shared", "1.0.0", "");
        serveJar("com.example", "shared", "1.0.0", null);

        Cas cas = new Cas(tempDir.resolve("cas"));
        Files.createDirectories(tempDir.resolve("cas"));
        ToolResolver resolver = new ToolResolver(RepoGroup.of(new MavenRepo("central", base, new Http(), cas)));

        ToolEnv env = resolver.resolve(Coordinate.of("com.example", "widget-cli", "1.0.0"), "widget", null);

        assertThat(env.classpath()).hasSize(2);
    }

    @Test
    void floating_latest_picks_the_highest_stable_version(@TempDir Path tempDir) throws Exception {
        serveMetadata("com.example", "widget-cli", "1.0.0", "1.1.0", "2.0.0-rc1", "1.2.0-SNAPSHOT");
        servePomAndJar("com.example", "widget-cli", "1.1.0", "com.example.Main");

        ToolResolver resolver = resolver(tempDir);
        ToolEnv env = resolver.resolve(
                cc.jumpkick.model.ToolCoordSpec.parse("com.example:widget-cli"), "widget", null, List.of());

        assertThat(env.primary().toGav()).isEqualTo("com.example:widget-cli:1.1.0");
        assertThat(env.mainClass()).isEqualTo("com.example.Main");
    }

    @Test
    void floating_selector_picks_the_highest_match(@TempDir Path tempDir) throws Exception {
        serveMetadata("com.example", "widget-cli", "1.0.0", "1.4.2", "2.1.0");
        servePomAndJar("com.example", "widget-cli", "1.4.2", "com.example.Main");

        ToolResolver resolver = resolver(tempDir);
        ToolEnv env = resolver.resolve(
                cc.jumpkick.model.ToolCoordSpec.parse("com.example:widget-cli@1.0"), "widget", null, List.of());

        // Caret ^1.0 — highest within the 1.x line, not 2.1.0.
        assertThat(env.primary().toGav()).isEqualTo("com.example:widget-cli:1.4.2");
    }

    @Test
    void unmatched_selector_reports_the_available_versions(@TempDir Path tempDir) {
        serveMetadata("com.example", "widget-cli", "1.0.0", "1.1.0");
        ToolResolver resolver = resolver(tempDir);
        assertThatThrownBy(() -> resolver.resolve(
                        cc.jumpkick.model.ToolCoordSpec.parse("com.example:widget-cli@^3.0"),
                        "widget",
                        null,
                        List.of()))
                .hasMessageContaining("no version of com.example:widget-cli matches")
                .hasMessageContaining("1.1.0");
    }

    @Test
    void with_extras_join_the_classpath(@TempDir Path tempDir) throws Exception {
        servePomAndJar("com.example", "widget-cli", "1.0.0", "com.example.Main");
        servePomAndJar("com.example", "extra", "2.0.0", null);

        ToolResolver resolver = resolver(tempDir);
        ToolEnv env = resolver.resolve(
                cc.jumpkick.model.ToolCoordSpec.parse("com.example:widget-cli:1.0.0"),
                "widget",
                null,
                List.of(cc.jumpkick.model.ToolCoordSpec.parse("com.example:extra:2.0.0")));

        assertThat(env.classpath()).hasSize(2);
        assertThat(env.classpath().getFirst().toString()).isNotEmpty();
    }

    @Test
    void native_classifier_binary_wins_over_the_jar(@TempDir Path tempDir) throws Exception {
        servePomAndJar("com.example", "widget-cli", "1.0.0", "com.example.Main");
        String classifier =
                "native-" + cc.jumpkick.jdk.HostPlatform.currentArch() + "-" + cc.jumpkick.jdk.HostPlatform.currentOs();
        served.put(
                "/com/example/widget-cli/1.0.0/widget-cli-1.0.0-" + classifier + ".exe",
                "#!/bin/sh\nexit 0\n".getBytes());

        ToolEnv env = resolver(tempDir).resolve(Coordinate.of("com.example", "widget-cli", "1.0.0"), "widget", null);

        assertThat(env.isNativeBinary()).isTrue();
        assertThat(env.mainClass()).isEqualTo(ToolEnv.NATIVE_BINARY);
        assertThat(env.classpath()).hasSize(1);
    }

    @Test
    void main_override_skips_the_native_probe(@TempDir Path tempDir) throws Exception {
        servePomAndJar("com.example", "widget-cli", "1.0.0", "com.example.Main");
        String classifier =
                "native-" + cc.jumpkick.jdk.HostPlatform.currentArch() + "-" + cc.jumpkick.jdk.HostPlatform.currentOs();
        served.put(
                "/com/example/widget-cli/1.0.0/widget-cli-1.0.0-" + classifier + ".exe",
                "#!/bin/sh\nexit 0\n".getBytes());

        ToolEnv env = resolver(tempDir)
                .resolve(Coordinate.of("com.example", "widget-cli", "1.0.0"), "widget", "com.example.Alt");

        assertThat(env.isNativeBinary()).isFalse();
        assertThat(env.mainClass()).isEqualTo("com.example.Alt");
    }

    private ToolResolver resolver(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cas"));
        try {
            Files.createDirectories(tempDir.resolve("cas"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new ToolResolver(RepoGroup.of(new MavenRepo("central", base, new Http(), cas)));
    }

    private void serveMetadata(String group, String artifact, String... versions) {
        StringBuilder vs = new StringBuilder();
        for (String v : versions) vs.append("      <version>").append(v).append("</version>\n");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <versions>
                %s    </versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, vs.toString());
        served.put("/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml", xml.getBytes());
    }

    private void servePomAndJar(String group, String artifact, String version, String mainClass) throws IOException {
        servePom(group, artifact, version, "");
        serveJar(group, artifact, version, mainClass);
    }

    private void servePom(String group, String artifact, String version, String depsXml) {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  %s
                </project>
                """.formatted(group, artifact, version, depsXml);
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
        served.put(path, pom.getBytes());
    }

    private void serveJar(String group, String artifact, String version, String mainClass) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos, mf)) {
            jos.putNextEntry(new ZipEntry("placeholder/Class.class"));
            jos.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jos.closeEntry();
        }
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
        served.put(path, baos.toByteArray());
    }
}
