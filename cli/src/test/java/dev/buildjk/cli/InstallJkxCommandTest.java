// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

@DisabledOnOs(OS.WINDOWS) // POSIX launcher only.
class InstallJkxCommandTest {

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
    void tool_install_writes_launcher_and_env_json(@TempDir Path tempDir) throws Exception {
        servePom("com.example", "widget-cli", "1.0.0");
        serveJar("com.example", "widget-cli", "1.0.0", "com.example.Main");

        int exit = run("tool", "install",
                "--home", tempDir.toString(),
                "--repo-url", base.toString(),
                "com.example:widget-cli:1.0.0");
        assertThat(exit).isEqualTo(0);

        Path launcher = tempDir.resolve("bin/widget-cli");
        Path envJson = tempDir.resolve("tools/envs/widget-cli/env.json");
        assertThat(launcher).exists();
        assertThat(Files.isExecutable(launcher)).isTrue();
        assertThat(envJson).exists();

        String script = Files.readString(launcher);
        assertThat(script).contains("com.example.Main");
        assertThat(script).contains("-cp ");

        String json = Files.readString(envJson);
        assertThat(json).contains("\"binName\": \"widget-cli\"");
        assertThat(json).contains("\"mainClass\": \"com.example.Main\"");
        assertThat(json).contains("\"primary\": \"com.example:widget-cli:1.0.0\"");
    }

    @Test
    void top_level_install_handles_a_maven_coord(@TempDir Path tempDir) throws Exception {
        // `jk install <coord>` is a first-class verb that produces the same
        // launcher + env layout as `jk tool install <coord>`.
        servePom("com.example", "widget-cli", "1.0.0");
        serveJar("com.example", "widget-cli", "1.0.0", "com.example.Main");

        int exit = run("install",
                "--home", tempDir.toString(),
                "--repo-url", base.toString(),
                "com.example:widget-cli:1.0.0");
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("bin/widget-cli")).exists();
        assertThat(tempDir.resolve("tools/envs/widget-cli/env.json")).exists();
    }

    @Test
    void tool_install_with_custom_bin_name(@TempDir Path tempDir) throws Exception {
        servePom("com.example", "widget-cli", "1.0.0");
        serveJar("com.example", "widget-cli", "1.0.0", "com.example.Main");

        int exit = run("tool", "install",
                "--home", tempDir.toString(),
                "--repo-url", base.toString(),
                "--bin", "widget",
                "com.example:widget-cli:1.0.0");
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("bin/widget")).exists();
        assertThat(tempDir.resolve("tools/envs/widget/env.json")).exists();
    }

    @Test
    void tool_install_with_main_override(@TempDir Path tempDir) throws Exception {
        servePom("com.example", "lib", "1.0.0");
        serveJar("com.example", "lib", "1.0.0", null); // no Main-Class

        int exit = run("tool", "install",
                "--home", tempDir.toString(),
                "--repo-url", base.toString(),
                "--main", "com.example.Alt",
                "com.example:lib:1.0.0");
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tempDir.resolve("bin/lib")))
                .contains("com.example.Alt");
    }

    @Test
    void jkx_resolves_and_returns_tool_exit_code(@TempDir Path tempDir) throws Exception {
        // A jar carrying a real Main that exits 0.
        Path realJar = buildRealRunnableJar(tempDir, "ToolMain");
        servePom("com.example", "tool", "1.0.0");
        served.put(mavenPath("com.example", "tool", "1.0.0", "jar"), Files.readAllBytes(realJar));

        int exit = run("jkx",
                "--home", tempDir.resolve("home").toString(),
                "--repo-url", base.toString(),
                "com.example:tool:1.0.0");
        // The synthetic ToolMain is a no-op; exit code is whatever it returns.
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void tool_run_is_jkx_under_the_canonical_name(@TempDir Path tempDir) throws Exception {
        Path realJar = buildRealRunnableJar(tempDir, "ToolMain");
        servePom("com.example", "tool", "1.0.0");
        served.put(mavenPath("com.example", "tool", "1.0.0", "jar"), Files.readAllBytes(realJar));

        int exit = run("tool", "run",
                "--home", tempDir.resolve("home").toString(),
                "--repo-url", base.toString(),
                "com.example:tool:1.0.0");
        assertThat(exit).isEqualTo(0);
    }

    // --- fixture helpers ----------------------------------------------------

    private void servePom(String group, String artifact, String version) {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
        served.put(mavenPath(group, artifact, version, "pom"), pom.getBytes());
    }

    private void serveJar(String group, String artifact, String version, String mainClass)
            throws IOException {
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
        served.put(mavenPath(group, artifact, version, "jar"), baos.toByteArray());
    }

    /**
     * Compile a tiny "main returns 0" class on the fly and wrap it in a jar
     * with the right MANIFEST.MF Main-Class. Used by the jkx exec test so
     * the spawned JVM has something real to run.
     */
    private static Path buildRealRunnableJar(Path tempDir, String className) throws Exception {
        Path src = tempDir.resolve(className + ".java");
        Files.writeString(src, "public class " + className
                + " { public static void main(String[] a) { System.exit(0); } }\n");
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, src.toString());
        if (rc != 0) throw new IllegalStateException("compile of " + src + " failed");
        Path classFile = src.resolveSibling(className + ".class");

        Path jar = tempDir.resolve("real-tool.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, className);
        try (var fos = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(fos, mf)) {
            jos.putNextEntry(new ZipEntry(className + ".class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }
        return jar;
    }

    private static String mavenPath(String group, String artifact, String version, String ext) {
        return "/" + group.replace('.', '/') + "/" + artifact + "/"
                + version + "/" + artifact + "-" + version + "." + ext;
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
