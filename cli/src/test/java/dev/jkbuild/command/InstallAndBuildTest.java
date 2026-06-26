// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.Jk;
import dev.jkbuild.util.Hashing;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for the jk install <file> → jk lock → jk build/check
 * pipeline. Verifies that locally-installed JARs are usable as compile-time
 * dependencies and that incremental compilation behaves correctly against them.
 */
class InstallAndBuildTest {

    @Test
    void build_compiles_against_locally_installed_jar(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");

        // Compile a library and package it as a JAR.
        Path libJar = buildJar(
                tempDir.resolve("lib"),
                "greeter-1.0.0",
                Map.of(
                        "lib.Greeter",
                        "package lib; public class Greeter { public String greet() { return \"hi\"; } }"));

        // Install via jk install.
        assertThat(run(
                        "install",
                        libJar.toString(),
                        "--group=lib",
                        "--name=greeter",
                        "--ver=1.0.0",
                        "--cache-dir=" + cache))
                .isEqualTo(0);

        // Create a project that depends on the installed JAR via sha256.
        Path projectDir = tempDir.resolve("app");
        Files.createDirectories(projectDir);
        String sha256 = Hashing.sha256Hex(Files.readAllBytes(libJar));
        Files.writeString(projectDir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "app"
                version = "0.1.0"
                java = 21

                [dependencies.main]
                greeter = { sha256 = "%s", group = "lib", version = "1.0.0" }
                """.formatted(sha256));
        Path src = projectDir.resolve("src/main/java/app/App.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package app;
                import lib.Greeter;
                public class App { public String run() { return new Greeter().greet(); } }
                """);

        // Lock and build.
        assertThat(run("lock", "-C", projectDir.toString(), "--cache-dir=" + cache))
                .isEqualTo(0);
        assertThat(run("build", "-C", projectDir.toString(), "--cache-dir=" + cache))
                .isEqualTo(0);

        assertThat(projectDir.resolve("target/app-0.1.0.jar")).exists();
    }

    @Test
    void second_build_is_a_cache_hit_when_nothing_changes(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");

        Path libJar = buildJar(
                tempDir.resolve("lib"),
                "mylib-1.0.0",
                Map.of("lib.Lib", "package lib; public class Lib { public int value() { return 1; } }"));
        assertThat(run(
                        "install",
                        libJar.toString(),
                        "--group=lib",
                        "--name=mylib",
                        "--ver=1.0.0",
                        "--cache-dir=" + cache))
                .isEqualTo(0);

        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir);
        String sha256 = Hashing.sha256Hex(Files.readAllBytes(libJar));
        Files.writeString(projectDir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "proj"
                version = "0.1.0"
                java = 21

                [dependencies.main]
                mylib = { sha256 = "%s", group = "lib", version = "1.0.0" }
                """.formatted(sha256));
        Path src = projectDir.resolve("src/main/java/app/Main.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package app;
                import lib.Lib;
                public class Main { public int v() { return new Lib().value(); } }
                """);

        assertThat(run("lock", "-C", projectDir.toString(), "--cache-dir=" + cache))
                .isEqualTo(0);
        assertThat(run("build", "-C", projectDir.toString(), "--cache-dir=" + cache))
                .isEqualTo(0);

        Path classFile = projectDir.resolve("target/classes/main/app/Main.class");
        assertThat(classFile).exists();
        long mtimeAfterFirst = Files.getLastModifiedTime(classFile).toMillis();

        // A no-change rebuild must not re-invoke javac — class mtime is unchanged.
        assertThat(run("build", "-C", projectDir.toString(), "--cache-dir=" + cache))
                .isEqualTo(0);
        assertThat(Files.getLastModifiedTime(classFile).toMillis()).isEqualTo(mtimeAfterFirst);
    }

    @Test
    void editing_a_source_triggers_incremental_recompile(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");

        Path libJar = buildJar(
                tempDir.resolve("lib"),
                "util-1.0.0",
                Map.of("lib.Util", "package lib; public class Util { public int x() { return 1; } }"));
        assertThat(run(
                        "install",
                        libJar.toString(),
                        "--group=lib",
                        "--name=util",
                        "--ver=1.0.0",
                        "--cache-dir=" + cache))
                .isEqualTo(0);

        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir);
        String sha256 = Hashing.sha256Hex(Files.readAllBytes(libJar));
        Files.writeString(projectDir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "proj"
                version = "0.1.0"
                java = 21

                [dependencies.main]
                util = { sha256 = "%s", group = "lib", version = "1.0.0" }
                """.formatted(sha256));
        Path src = projectDir.resolve("src/main/java/app/Main.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package app;
                import lib.Util;
                public class Main { public int v() { return new Util().x(); } }
                """);

        assertThat(run("lock", "-C", projectDir.toString(), "--cache-dir=" + cache))
                .isEqualTo(0);
        assertThat(run("build", "-C", projectDir.toString(), "--cache-dir=" + cache))
                .isEqualTo(0);

        Path classFile = projectDir.resolve("target/classes/main/app/Main.class");
        long mtimeAfterFirst = Files.getLastModifiedTime(classFile).toMillis();

        // Edit the source (body-only change) and bump its mtime past the stamp.
        Files.writeString(src, """
                package app;
                import lib.Util;
                public class Main { public int v() { return new Util().x() + 1; } }
                """);
        Files.setLastModifiedTime(src, java.nio.file.attribute.FileTime.fromMillis(mtimeAfterFirst + 5_000));

        assertThat(run("build", "-C", projectDir.toString(), "--cache-dir=" + cache))
                .isEqualTo(0);
        assertThat(Files.getLastModifiedTime(classFile).toMillis()).isNotEqualTo(mtimeAfterFirst);
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Compile {@code fqcn → source} entries and package the resulting
     * {@code .class} files into {@code workDir/<name>.jar}.
     */
    private static Path buildJar(Path workDir, String name, Map<String, String> sources) throws IOException {
        Path src = workDir.resolve("src");
        Path out = workDir.resolve("out");
        Files.createDirectories(out);
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path f = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f.toString());
        }
        var javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("no system javac");
        List<String> args = new ArrayList<>(List.of("-d", out.toString()));
        args.addAll(files);
        if (javac.run(null, null, null, args.toArray(new String[0])) != 0) {
            throw new IllegalStateException("javac failed compiling " + name);
        }
        // Collect .class bytes
        Map<String, byte[]> classes = new HashMap<>();
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!p.toString().endsWith(".class")) continue;
                String rel = out.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                classes.put(
                        rel.substring(0, rel.length() - ".class".length()).replace('/', '.'), Files.readAllBytes(p));
            }
        }
        // Package into a JAR
        Path jar = workDir.resolve(name + ".jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey().replace('.', '/') + ".class"));
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
        return jar;
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
