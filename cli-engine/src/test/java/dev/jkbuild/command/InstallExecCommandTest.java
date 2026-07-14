// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cli.Jk;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS) // POSIX launcher only.
class InstallExecCommandTest {

    // These tests drive the real fetch pipeline against a mock Maven server; fetched
    // artifacts mirror into the Maven local repo. Point that at a throwaway dir (see
    // M2Dirs) so stub artifacts never overwrite the developer's real ~/.m2 — the
    // fixture reuses real coordinates (junit-jupiter et al).
    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

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
    void tool_install_writes_launcher_and_env_json(@TempDir Path tempDir) throws Exception {
        servePom("com.example", "widget-cli", "1.0.0");
        serveJar("com.example", "widget-cli", "1.0.0", "com.example.Main");

        Path bin = tempDir.resolve("bin");
        int exit = run(
                "tool",
                "install",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                bin.toString(),
                "--repo-url",
                base.toString(),
                "com.example:widget-cli:1.0.0");
        assertThat(exit).isEqualTo(0);

        Path launcher = bin.resolve("widget-cli");
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
        // Provenance: how the tool was installed (plan §3).
        assertThat(json).contains("\"kind\": \"gav\"");
        assertThat(json).contains("\"spec\": \"com.example:widget-cli:1.0.0\"");
    }

    @Test
    void top_level_install_handles_a_maven_coord(@TempDir Path tempDir) throws Exception {
        // `jk install <coord>` is a first-class verb that produces the same
        // launcher + env layout as `jk tool install <coord>`.
        servePom("com.example", "widget-cli", "1.0.0");
        serveJar("com.example", "widget-cli", "1.0.0", "com.example.Main");

        Path bin = tempDir.resolve("bin");
        int exit = run(
                "install",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                bin.toString(),
                "--repo-url",
                base.toString(),
                "com.example:widget-cli:1.0.0");
        assertThat(exit).isEqualTo(0);
        assertThat(bin.resolve("widget-cli")).exists();
        assertThat(tempDir.resolve("tools/envs/widget-cli/env.json")).exists();
    }

    @Test
    void tool_install_with_custom_bin_name(@TempDir Path tempDir) throws Exception {
        servePom("com.example", "widget-cli", "1.0.0");
        serveJar("com.example", "widget-cli", "1.0.0", "com.example.Main");

        Path bin = tempDir.resolve("bin");
        int exit = run(
                "tool",
                "install",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                bin.toString(),
                "--repo-url",
                base.toString(),
                "--bin",
                "widget",
                "com.example:widget-cli:1.0.0");
        assertThat(exit).isEqualTo(0);
        assertThat(bin.resolve("widget")).exists();
        assertThat(tempDir.resolve("tools/envs/widget/env.json")).exists();
    }

    @Test
    void tool_install_with_main_override(@TempDir Path tempDir) throws Exception {
        servePom("com.example", "lib", "1.0.0");
        serveJar("com.example", "lib", "1.0.0", null); // no Main-Class

        Path bin = tempDir.resolve("bin");
        int exit = run(
                "tool",
                "install",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                bin.toString(),
                "--repo-url",
                base.toString(),
                "--main",
                "com.example.Alt",
                "com.example:lib:1.0.0");
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(bin.resolve("lib"))).contains("com.example.Alt");
    }

    @Test
    void tool_run_resolves_and_returns_tool_exit_code(@TempDir Path tempDir) throws Exception {
        // A jar carrying a real Main that exits 0.
        Path realJar = buildRealRunnableJar(tempDir, "ToolMain");
        servePom("com.example", "tool", "1.0.0");
        served.put(mavenPath("com.example", "tool", "1.0.0", "jar"), Files.readAllBytes(realJar));

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--repo-url",
                base.toString(),
                "com.example:tool:1.0.0");
        // The synthetic ToolMain is a no-op; exit code is whatever it returns.
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void tool_install_version_less_coord_resolves_latest_stable(@TempDir Path tempDir) throws Exception {
        serveMetadata("com.example", "widget-cli", "1.0.0", "1.1.0", "2.0.0-rc1");
        servePom("com.example", "widget-cli", "1.1.0");
        serveJar("com.example", "widget-cli", "1.1.0", "com.example.Main");

        Path bin = tempDir.resolve("bin");
        int exit = run(
                "tool",
                "install",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                bin.toString(),
                "--repo-url",
                base.toString(),
                "com.example:widget-cli");
        assertThat(exit).isEqualTo(0);
        String json = Files.readString(tempDir.resolve("tools/envs/widget-cli/env.json"));
        // latest = highest stable — 2.0.0-rc1 is skipped.
        assertThat(json).contains("\"primary\": \"com.example:widget-cli:1.1.0\"");
    }

    @Test
    void tool_install_catalog_short_name_resolves_via_libs_toml(@TempDir Path tempDir) throws Exception {
        serveMetadata("com.example", "widget-cli", "1.0.0");
        servePom("com.example", "widget-cli", "1.0.0");
        serveJar("com.example", "widget-cli", "1.0.0", "com.example.Main");

        // The user-local catalog layer (JK_HOME is redirected per-module by the build).
        Path jkHome = Path.of(System.getenv("JK_HOME"));
        Files.createDirectories(jkHome);
        Path libsToml = jkHome.resolve("libs.toml");
        Files.writeString(libsToml, "[libraries]\ntesttool-fixture = \"com.example:widget-cli\"\n");
        try {
            Path bin = tempDir.resolve("bin");
            int exit = run(
                    "tool",
                    "install",
                    "--cache-dir",
                    tempDir.resolve("cache").toString(),
                    "--state-dir",
                    tempDir.toString(),
                    "--bin-dir",
                    bin.toString(),
                    "--repo-url",
                    base.toString(),
                    "testtool-fixture");
            assertThat(exit).isEqualTo(0);
            // The catalog name is the launcher name; the module resolved through the catalog.
            assertThat(bin.resolve("testtool-fixture")).exists();
            String json = Files.readString(tempDir.resolve("tools/envs/testtool-fixture/env.json"));
            assertThat(json).contains("\"primary\": \"com.example:widget-cli:1.0.0\"");
        } finally {
            Files.deleteIfExists(libsToml);
        }
    }

    @Test
    void tool_install_with_injects_extra_deps(@TempDir Path tempDir) throws Exception {
        servePom("com.example", "widget-cli", "1.0.0");
        serveJar("com.example", "widget-cli", "1.0.0", "com.example.Main");
        servePom("com.example", "extra", "2.0.0");
        serveJar("com.example", "extra", "2.0.0", null);

        Path bin = tempDir.resolve("bin");
        int exit = run(
                "tool",
                "install",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                bin.toString(),
                "--repo-url",
                base.toString(),
                "--with",
                "com.example:extra:2.0.0",
                "com.example:widget-cli:1.0.0");
        assertThat(exit).isEqualTo(0);
        // The classpath is absolute CAS paths (content-hashed, no artifact names):
        // primary + the --with extra = two entries.
        String launcher = Files.readString(bin.resolve("widget-cli"));
        String cp = launcher.lines()
                .filter(l -> l.contains("-cp "))
                .findFirst()
                .orElseThrow()
                .replace("-cp", "")
                .replace("\\", "")
                .trim();
        assertThat(cp.split(":")).hasSize(2);
    }

    @Test
    void unknown_catalog_name_fails_with_usage_error(@TempDir Path tempDir) {
        int exit = run(
                "tool",
                "install",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                tempDir.resolve("bin").toString(),
                "no-such-tool-name");
        assertThat(exit).isEqualTo(64); // Exit.USAGE — catalog miss, message names the fix
    }

    @Test
    void phase_gated_targets_point_at_the_plan(@TempDir Path tempDir) {
        // URL / git / JBang-alias targets classify but aren't supported yet — usage error, not a
        // coordinate parse error.
        assertThat(run("tool", "run", "https://example.com/tool.jar")).isEqualTo(64);
        assertThat(run("tool", "run", "gh:acme/widgets")).isEqualTo(64);
        assertThat(run("tool", "run", "hello@jbangdev/jbang-catalog")).isEqualTo(64);
    }

    @Test
    void tool_install_of_a_script_snapshots_an_env(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Hello.java");
        Files.writeString(script, """
                public class Hello {
                    public static void main(String[] args) { System.exit(0); }
                }
                """);

        Path bin = tempDir.resolve("bin");
        int exit = run(
                "tool",
                "install",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                bin.toString(),
                script.toString());
        assertThat(exit).isEqualTo(0);

        // The env holds an immutable snapshot: deleting the source must not break the launcher.
        Files.delete(script);
        Path launcher = bin.resolve("hello");
        assertThat(launcher).exists();
        Path classes = tempDir.resolve("tools/envs/hello/classes/Hello.class");
        assertThat(classes).exists();
        String json = Files.readString(tempDir.resolve("tools/envs/hello/env.json"));
        assertThat(json).contains("\"mainClass\": \"Hello\"");
        // Launcher classpath points at the snapshot, not the script-cache.
        assertThat(Files.readString(launcher)).contains("tools/envs/hello/classes");

        Process p = new ProcessBuilder(launcher.toString()).start();
        assertThat(p.waitFor()).isEqualTo(0);
    }

    @Test
    void tool_install_of_a_jar_copies_it_into_the_env(@TempDir Path tempDir) throws Exception {
        Path jar = buildRealRunnableJar(tempDir, "JarMain");
        Path bin = tempDir.resolve("bin");
        int exit = run(
                "tool",
                "install",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                bin.toString(),
                "--bin",
                "jartool",
                jar.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("tools/envs/jartool/real-tool.jar")).exists();

        Files.delete(jar);
        Process p = new ProcessBuilder(bin.resolve("jartool").toString()).start();
        assertThat(p.waitFor()).isEqualTo(0);
    }

    @Test
    void tool_install_of_a_project_dir_delegates_to_the_app_pipeline(@TempDir Path tempDir) throws Exception {
        // Convergence (plan §9): `jk tool install <dir>` == `jk install` run in that dir.
        run("new", "--group", "com.example", "--name", "widget", "--executable", "--layout", "traditional",
                tempDir.resolve("proj").toString());
        Path src = tempDir.resolve("proj/src/main/java/com/example/Main.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package com.example;
                public final class Main {
                    public static void main(String[] args) { System.exit(0); }
                }
                """);

        Path bin = tempDir.resolve("bin");
        int exit = run(
                "tool",
                "install",
                "--cache-dir",
                SharedTestCache.arg(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                bin.toString(),
                tempDir.resolve("proj").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(bin.resolve("widget")).exists();
    }

    @Test
    void tool_install_of_a_non_project_dir_is_a_config_error(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("empty");
        Files.createDirectories(dir);
        assertThat(run("tool", "install", dir.toString())).isEqualTo(2); // CONFIG: no jk.toml
    }

    @Test
    void tool_install_from_a_trusted_url_snapshots_an_env(@TempDir Path tempDir) throws Exception {
        served.put("/r/Web.java", """
                public class Web {
                    public static void main(String[] args) { System.exit(0); }
                }
                """.getBytes());

        run("trust", "add", "--state-dir", tempDir.toString(), base.toString() + "/");
        Path bin = tempDir.resolve("bin");
        int exit = run(
                "tool",
                "install",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                bin.toString(),
                base + "/r/Web.java");
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("tools/envs/web/classes/Web.class")).exists();

        Process p = new ProcessBuilder(bin.resolve("web").toString()).start();
        assertThat(p.waitFor()).isEqualTo(0);
    }

    @Test
    void tool_install_from_an_untrusted_url_is_rejected(@TempDir Path tempDir) throws Exception {
        served.put("/r/Web.java", "public class Web {}".getBytes());
        int exit = run(
                "tool",
                "install",
                "--state-dir",
                tempDir.toString(),
                base + "/r/Web.java");
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void native_classifier_tool_installs_a_direct_exec_launcher(@TempDir Path tempDir) throws Exception {
        // PRD §20.4: a published native binary for this platform beats the JVM path.
        servePom("com.example", "fastcli", "1.0.0");
        String classifier = "native-" + dev.jkbuild.jdk.HostPlatform.currentArch() + "-"
                + dev.jkbuild.jdk.HostPlatform.currentOs();
        served.put(
                "/com/example/fastcli/1.0.0/fastcli-1.0.0-" + classifier + ".exe",
                "#!/bin/sh\nexit 7\n".getBytes());

        Path bin = tempDir.resolve("bin");
        int exit = run(
                "tool",
                "install",
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                bin.toString(),
                "--repo-url",
                base.toString(),
                "com.example:fastcli:1.0.0");
        assertThat(exit).isEqualTo(0);
        String json = Files.readString(tempDir.resolve("tools/envs/fastcli/env.json"));
        assertThat(json).contains("\"mainClass\": \"native-binary\"");

        Process p = new ProcessBuilder(bin.resolve("fastcli").toString()).start();
        assertThat(p.waitFor()).isEqualTo(7);
    }

    // --- fixture helpers ----------------------------------------------------

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
        served.put(mavenPath(group, artifact, version, "jar"), baos.toByteArray());
    }

    /**
     * Compile a tiny "main returns 0" class on the fly and wrap it in a jar with the right
     * MANIFEST.MF Main-Class. Used by the exec tests so the spawned JVM has something real to run.
     */
    private static Path buildRealRunnableJar(Path tempDir, String className) throws Exception {
        Path src = tempDir.resolve(className + ".java");
        Files.writeString(
                src, "public class " + className + " { public static void main(String[] a) { System.exit(0); } }\n");
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
        return "/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version
                + "."
                + ext;
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
