// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for composite ({@code path}) source dependencies: built from source
 * and injected onto the consumer's classpath (jk's {@code includeBuild} analog).
 * Branch-git composite deps share the same code path once cloned, exercised via
 * {@link GitSourceMaterializerTest}-style fixtures elsewhere.
 */
class CompositeDepResolverTest {

    private static final Path JAVA_HOME = Path.of(System.getProperty("java.home"));

    private static void lib(Path dir, String group, String name, String body) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group   = "%s"
                name    = "%s"
                version = "0.1.0"
                jdk     = 25
                java    = 25
                """.formatted(group, name));
        Path src = dir.resolve("src/main/java/lib/Lib.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, body);
    }

    private static JkBuild parse(Path dir) throws IOException {
        return JkBuildParser.parse(Files.readString(dir.resolve("jk.toml")));
    }

    private static CompositeDepResolver.Result resolve(Path consumerDir, Path cache)
            throws IOException, InterruptedException {
        return CompositeDepResolver.resolve(consumerDir, parse(consumerDir),
                Set.of(Scope.MAIN), ClasspathResolver.COMPILE_MAIN,
                new Cas(cache.resolve("cas")), JAVA_HOME, "test", cache.resolve("git"));
    }

    @Test
    void path_dep_builds_from_source_and_contributes_its_jar(@TempDir Path tmp) throws Exception {
        Path lib = tmp.resolve("lib");
        lib(lib, "com.example", "lib", "package lib; public class Lib { public static int v() { return 7; } }");

        Path app = tmp.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                jdk     = 25
                java    = 25

                [dependencies.main]
                lib = { group = "com.example", name = "lib", path = "../lib" }
                """);

        CompositeDepResolver.Result r = resolve(app, tmp.resolve("cache"));

        assertThat(r.errors()).isEmpty();
        Path expectedJar = BuildLayout.of(lib, parse(lib)).mainJar();
        assertThat(r.jars()).contains(expectedJar);
        assertThat(expectedJar).exists();
    }

    @Test
    void simple_layout_path_dep_builds(@TempDir Path tmp) throws Exception {
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib.resolve("src"));
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "lib"
                version = "0.1.0"
                layout  = "simple"
                jdk     = 25
                java    = 25
                """);
        Files.writeString(lib.resolve("src/Lib.java"),
                "package lib; public class Lib {}");

        Path app = tmp.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                jdk     = 25
                java    = 25

                [dependencies.main]
                lib = { group = "com.example", name = "lib", path = "../lib" }
                """);

        CompositeDepResolver.Result r = resolve(app, tmp.resolve("cache"));

        assertThat(r.errors()).isEmpty();
        assertThat(BuildLayout.of(lib, parse(lib)).mainJar()).exists();
    }

    @Test
    void coordinate_mismatch_is_an_error(@TempDir Path tmp) throws Exception {
        Path lib = tmp.resolve("lib");
        lib(lib, "com.example", "actual-name", "package lib; public class Lib {}");

        Path app = tmp.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                jdk     = 25
                java    = 25

                [dependencies.main]
                lib = { group = "com.example", name = "declared-name", path = "../lib" }
                """);

        CompositeDepResolver.Result r = resolve(app, tmp.resolve("cache"));

        assertThat(r.errors()).anyMatch(e -> e.contains("coordinate"));
        assertThat(r.jars()).isEmpty();
    }

    @Test
    void cycle_is_detected(@TempDir Path tmp) throws Exception {
        // a → b → a (path deps; no source references, so each compiles independently).
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        Files.createDirectories(a);
        Files.createDirectories(b);
        Files.writeString(a.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "a"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies.main]
                b = { group = "com.example", name = "b", path = "../b" }
                """);
        Files.writeString(b.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "b"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies.main]
                a = { group = "com.example", name = "a", path = "../a" }
                """);
        Files.createDirectories(a.resolve("src/main/java/a"));
        Files.writeString(a.resolve("src/main/java/a/A.java"), "package a; public class A {}");
        Files.createDirectories(b.resolve("src/main/java/b"));
        Files.writeString(b.resolve("src/main/java/b/B.java"), "package b; public class B {}");

        CompositeDepResolver.Result r = resolve(a, tmp.resolve("cache"));

        assertThat(r.errors()).anyMatch(e -> e.contains("cycle"));
    }

    @Test
    void transitive_path_chain_builds_all_targets(@TempDir Path tmp) throws Exception {
        Path leaf = tmp.resolve("leaf");
        lib(leaf, "com.example", "leaf", "package lib; public class Lib { public static int v() { return 1; } }");

        Path mid = tmp.resolve("mid");
        Files.createDirectories(mid);
        Files.writeString(mid.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "mid"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies.main]
                leaf = { group = "com.example", name = "leaf", path = "../leaf" }
                """);
        Files.createDirectories(mid.resolve("src/main/java/mid"));
        Files.writeString(mid.resolve("src/main/java/mid/Mid.java"), "package mid; public class Mid {}");

        Path app = tmp.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies.main]
                mid = { group = "com.example", name = "mid", path = "../mid" }
                """);

        CompositeDepResolver.Result r = resolve(app, tmp.resolve("cache"));

        assertThat(r.errors()).isEmpty();
        assertThat(r.jars())
                .contains(BuildLayout.of(mid, parse(mid)).mainJar())
                .contains(BuildLayout.of(leaf, parse(leaf)).mainJar());
    }
}
