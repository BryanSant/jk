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
 * {@link CompositeLocator} finds already-built composite jars without building.
 */
class CompositeLocatorTest {

    private static JkBuild project(Path dir, String name, String... pathDeps) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("""
                [project]
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                jdk     = 21
                java    = 21
                """.formatted(name));
        if (pathDeps.length > 0) {
            sb.append("\n[dependencies.main]\n");
            for (String d : pathDeps) {
                sb.append("%s = { group = \"com.example\", name = \"%s\", path = \"../%s\" }\n"
                        .formatted(d, d, d));
            }
        }
        Files.writeString(dir.resolve("jk.toml"), sb.toString());
        return JkBuildParser.parse(Files.readString(dir.resolve("jk.toml")));
    }

    /** Place a (fake) built main jar at the project's BuildLayout.mainJar() path. */
    private static Path fakeBuiltJar(Path dir, JkBuild p) throws IOException {
        Path jar = BuildLayout.of(dir, p).mainJar();
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "built");
        return jar;
    }

    private static CompositeLocator.Located locate(Path consumerDir, JkBuild consumer, Path tmp)
            throws IOException, InterruptedException {
        return CompositeLocator.locate(consumerDir, consumer, Set.of(Scope.MAIN),
                ClasspathResolver.COMPILE_MAIN, new Cas(tmp.resolve("cas")), tmp.resolve("git"));
    }

    @Test
    void locates_a_built_path_dep_jar_without_building(@TempDir Path tmp) throws Exception {
        JkBuild lib = project(tmp.resolve("lib"), "lib");
        Path libJar = fakeBuiltJar(tmp.resolve("lib"), lib);
        JkBuild app = project(tmp.resolve("app"), "app", "lib");

        CompositeLocator.Located r = locate(tmp.resolve("app"), app, tmp);

        assertThat(r.missing()).isEmpty();
        assertThat(r.jars()).containsExactly(libJar);
        // Locate must never invoke a compiler — the lib's build dir stays absent.
        assertThat(tmp.resolve("lib/target/build")).doesNotExist();
    }

    @Test
    void reports_missing_when_jar_not_built(@TempDir Path tmp) throws Exception {
        project(tmp.resolve("lib"), "lib");            // exists, but no jar produced
        JkBuild app = project(tmp.resolve("app"), "app", "lib");

        CompositeLocator.Located r = locate(tmp.resolve("app"), app, tmp);

        assertThat(r.jars()).isEmpty();
        assertThat(r.missing()).anyMatch(m -> m.contains("com.example:lib") && m.contains("not built"));
    }

    @Test
    void locates_transitive_path_jars(@TempDir Path tmp) throws Exception {
        JkBuild leaf = project(tmp.resolve("leaf"), "leaf");
        Path leafJar = fakeBuiltJar(tmp.resolve("leaf"), leaf);
        JkBuild mid = project(tmp.resolve("mid"), "mid", "leaf");
        Path midJar = fakeBuiltJar(tmp.resolve("mid"), mid);
        JkBuild app = project(tmp.resolve("app"), "app", "mid");

        CompositeLocator.Located r = locate(tmp.resolve("app"), app, tmp);

        assertThat(r.missing()).isEmpty();
        assertThat(r.jars()).containsExactlyInAnyOrder(midJar, leafJar);
    }
}
