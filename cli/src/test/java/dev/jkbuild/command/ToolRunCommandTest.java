// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cli.Jk;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk tool run <file>} — file-execution modes (.java/.kt/.kts/.jar).
 * (Coordinate-target behavior lives alongside the tool-install integration
 * tests.) These moved here from {@code RunCommandTest} when {@code jk run}
 * stopped interpreting file arguments.
 */
class ToolRunCommandTest {

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
    void runs_a_solo_script_with_no_deps(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Solo.java");
        Files.writeString(script, """
                public class Solo {
                    public static void main(String[] args) {
                        System.exit(0);
                    }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(exit).isEqualTo(0);

        // Cache dir was populated.
        Path scriptCache = tempDir.resolve("home/script-cache");
        assertThat(scriptCache).exists();
        assertThat(Files.list(scriptCache).findAny()).isPresent();
    }

    @Test
    void exit_code_is_propagated_from_script(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Fail.java");
        Files.writeString(script, """
                public class Fail {
                    public static void main(String[] args) {
                        System.exit(42);
                    }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(exit).isEqualTo(42);
    }

    @Test
    void script_args_are_forwarded(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Echo.java");
        Files.writeString(script, """
                public class Echo {
                    public static void main(String[] args) {
                        // Use the count of args as the exit code so the test can read it.
                        System.exit(args.length);
                    }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString(),
                "one",
                "two",
                "three");
        assertThat(exit).isEqualTo(3);
    }

    @Test
    void resolves_dep_from_header(@TempDir Path tempDir) throws Exception {
        // Build a tiny "Greeter" jar that exposes a method our script will call.
        servePom("com.example", "greeter", "1.0.0");
        served.put(mavenPath("com.example", "greeter", "1.0.0", "jar"), Files.readAllBytes(buildGreeterJar(tempDir)));

        Path script = tempDir.resolve("CallGreeter.java");
        Files.writeString(script, """
                //jk dep com.example:greeter:1.0.0

                public class CallGreeter {
                    public static void main(String[] args) {
                        // Greeter.exitCode() returns 17 — propagate as our exit code.
                        System.exit(com.example.Greeter.exitCode());
                    }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                "--repo-url",
                base.toString(),
                script.toString());
        assertThat(exit).isEqualTo(17);
    }

    @Test
    void second_run_uses_cached_classes(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Cached.java");
        Files.writeString(script, """
                public class Cached {
                    public static void main(String[] args) { System.exit(0); }
                }
                """, StandardCharsets.UTF_8);

        int firstExit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(firstExit).isEqualTo(0);

        // Find the cache directory created by the first run.
        Path scriptCache = tempDir.resolve("home/script-cache");
        Path hashDir = Files.list(scriptCache).findFirst().orElseThrow();
        Path classFile = hashDir.resolve("classes/Cached.class");
        long firstMtime = Files.getLastModifiedTime(classFile).toMillis();

        // Re-run without --force-recompile; classes should not be rewritten.
        Thread.sleep(50);
        int secondExit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(secondExit).isEqualTo(0);
        long secondMtime = Files.getLastModifiedTime(classFile).toMillis();
        assertThat(secondMtime).isEqualTo(firstMtime);
    }

    @Test
    void force_recompile_flag_invalidates_cache(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Forced.java");
        Files.writeString(script, """
                public class Forced { public static void main(String[] args) {} }
                """, StandardCharsets.UTF_8);

        run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        Path scriptCache = tempDir.resolve("home/script-cache");
        Path classFile = Files.list(scriptCache).findFirst().orElseThrow().resolve("classes/Forced.class");
        long firstMtime = Files.getLastModifiedTime(classFile).toMillis();

        Thread.sleep(50);
        run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                "--force-recompile",
                script.toString());
        long secondMtime = Files.getLastModifiedTime(classFile).toMillis();
        assertThat(secondMtime).isGreaterThan(firstMtime);
    }

    @Test
    void missing_script_returns_no_input(@TempDir Path tempDir) {
        int exit = run("tool", "run", tempDir.resolve("missing.java").toString());
        assertThat(exit).isEqualTo(66);
    }

    @Test
    void compile_failure_returns_nonzero(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Broken.java");
        Files.writeString(script, "public class Broken { not java }\n");
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void runs_a_self_contained_jar(@TempDir Path tempDir) throws Exception {
        // Build a tiny runnable jar with Main-Class set.
        Path jar = buildExitJar(tempDir, "RunMe", /*exitCode=*/ 0);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                jar.toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void jar_exit_code_is_propagated(@TempDir Path tempDir) throws Exception {
        Path jar = buildExitJar(tempDir, "RunMe", /*exitCode=*/ 7);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                jar.toString());
        assertThat(exit).isEqualTo(7);
    }

    @Test
    void jar_args_are_forwarded(@TempDir Path tempDir) throws Exception {
        Path jar = buildArgCountJar(tempDir, "Echo");

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                jar.toString(),
                "one",
                "two");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void jar_without_main_class_returns_data_error(@TempDir Path tempDir) throws Exception {
        // Jar with no Main-Class attribute → EX_DATAERR.
        Path jar = tempDir.resolve("no-main.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var fos = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(fos, mf)) {
            jos.putNextEntry(new ZipEntry("placeholder.txt"));
            jos.write("hi".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                jar.toString());
        assertThat(exit).isEqualTo(65);
    }

    @Test
    void jar_not_found_returns_no_input(@TempDir Path tempDir) {
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                tempDir.resolve("missing.jar").toString());
        assertThat(exit).isEqualTo(66);
    }

    /**
     * Build a runnable jar whose {@code Main-Class} calls
     * {@code System.exit(exitCode)}. Used by .jar mode integration tests.
     */
    private static Path buildExitJar(Path tempDir, String className, int exitCode) throws Exception {
        Path src = tempDir.resolve(className + ".java");
        Files.writeString(
                src,
                "public class " + className + " { public static void main(String[] a) { System.exit(" + exitCode
                        + "); } }\n");
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, src.toString());
        if (rc != 0) throw new IllegalStateException("compile of " + src + " failed");
        Path classFile = src.resolveSibling(className + ".class");

        Path jar = tempDir.resolve(className.toLowerCase() + ".jar");
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

    /** Jar whose main exits with {@code args.length} — for testing arg forwarding. */
    private static Path buildArgCountJar(Path tempDir, String className) throws Exception {
        Path src = tempDir.resolve(className + ".java");
        Files.writeString(
                src,
                "public class " + className + " { public static void main(String[] a) { System.exit(a.length); } }\n");
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, src.toString());
        if (rc != 0) throw new IllegalStateException("compile of " + src + " failed");
        Path classFile = src.resolveSibling(className + ".class");

        Path jar = tempDir.resolve(className.toLowerCase() + ".jar");
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

    // --- helpers -----------------------------------------------------------

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

    /**
     * Build a jar containing {@code com.example.Greeter} with an
     * {@code exitCode()} method returning 17, used by the dep-resolution
     * test as something the script can call.
     */
    private static Path buildGreeterJar(Path tempDir) throws Exception {
        Path src = tempDir.resolve("Greeter.java");
        Files.writeString(src, """
                package com.example;
                public final class Greeter {
                    private Greeter() {}
                    public static int exitCode() { return 17; }
                }
                """);
        Path out = tempDir.resolve("greeter-classes");
        Files.createDirectories(out);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, "-d", out.toString(), src.toString());
        if (rc != 0) throw new IllegalStateException("compile of Greeter failed");
        Path classFile = out.resolve("com/example/Greeter.class");

        Path jar = tempDir.resolve("greeter.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var fos = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(fos, mf)) {
            jos.putNextEntry(new ZipEntry("com/example/Greeter.class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }
        return jar;
    }

    private static String mavenPath(String group, String artifact, String version, String ext) {
        return "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + "."
                + ext;
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
