// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Graph-resolution coverage for the unified composite build graph (path deps;
 * branch-git shares the code path once cloned). No building happens here —
 * {@link BuildGraph#resolve} only resolves units + order.
 */
class BuildGraphTest {

    /** A project that path-depends on the given siblings (by dir name == artifact). */
    private static void project(Path dir, String name, String... pathDeps) throws IOException {
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
    }

    private static BuildGraph.Result resolve(Path entryDir, Path tmp) throws IOException, InterruptedException {
        JkBuild entry = JkBuildParser.parse(Files.readString(entryDir.resolve("jk.toml")));
        return BuildGraph.resolve(entryDir, entry, tmp.resolve("git"));
    }

    private static List<String> coords(BuildGraph.Result r) {
        return r.topoOrder().stream().map(BuildGraph.BuildUnit::coord).toList();
    }

    @Test
    void path_chain_orders_dependencies_first(@TempDir Path tmp) throws Exception {
        project(tmp.resolve("c"), "c");
        project(tmp.resolve("b"), "b", "c");
        project(tmp.resolve("a"), "a", "b");

        BuildGraph.Result r = resolve(tmp.resolve("a"), tmp);

        assertThat(r.errors()).isEmpty();
        // c before b before a (prereqs first).
        List<String> order = coords(r);
        assertThat(order).containsExactly("com.example:c", "com.example:b", "com.example:a");
    }

    @Test
    void diamond_target_is_built_once(@TempDir Path tmp) throws Exception {
        project(tmp.resolve("d"), "d");
        project(tmp.resolve("b"), "b", "d");
        project(tmp.resolve("cc"), "cc", "d");
        project(tmp.resolve("a"), "a", "b", "cc");

        BuildGraph.Result r = resolve(tmp.resolve("a"), tmp);

        assertThat(r.errors()).isEmpty();
        assertThat(coords(r)).containsExactlyInAnyOrder(
                "com.example:a", "com.example:b", "com.example:cc", "com.example:d");
        // d (shared) appears exactly once and before both b and cc.
        List<String> order = coords(r);
        assertThat(order.indexOf("com.example:d")).isLessThan(order.indexOf("com.example:b"));
        assertThat(order.indexOf("com.example:d")).isLessThan(order.indexOf("com.example:cc"));
        assertThat(order.stream().filter("com.example:d"::equals).count()).isEqualTo(1);
    }

    @Test
    void cycle_is_reported(@TempDir Path tmp) throws Exception {
        project(tmp.resolve("a"), "a", "b");
        project(tmp.resolve("b"), "b", "a");

        BuildGraph.Result r = resolve(tmp.resolve("a"), tmp);

        assertThat(r.errors()).anyMatch(e -> e.contains("cycle"));
        assertThat(r.topoOrder()).isEmpty();
    }

    @Test
    void coordinate_mismatch_is_reported(@TempDir Path tmp) throws Exception {
        project(tmp.resolve("real"), "real");
        // app declares the dep under the wrong name but points at ../real.
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app/jk.toml"), """
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 21
                java = 21

                [dependencies.main]
                wrong = { group = "com.example", name = "wrong", path = "../real" }
                """);

        BuildGraph.Result r = resolve(tmp.resolve("app"), tmp);

        assertThat(r.errors()).anyMatch(e -> e.contains("coordinate"));
    }

    @Test
    void missing_target_jk_toml_is_reported(@TempDir Path tmp) throws Exception {
        project(tmp.resolve("a"), "a", "ghost"); // ../ghost has no jk.toml

        BuildGraph.Result r = resolve(tmp.resolve("a"), tmp);

        assertThat(r.errors()).anyMatch(e -> e.contains("no jk.toml"));
    }

    @Test
    void workspace_members_become_units_in_dependency_order(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 21
                java = 21

                [workspace]
                members = ["core", "app"]
                """);
        // app depends on core (sibling), so core builds first.
        project(tmp.resolve("core"), "core");
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app/jk.toml"), """
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 21
                java = 21

                [dependencies.main]
                core = { group = "com.example", name = "core", version = "1.0.0" }
                """);

        BuildGraph.Result r = resolve(tmp, tmp);

        assertThat(r.errors()).isEmpty();
        List<String> order = coords(r);
        assertThat(order).containsExactlyInAnyOrder("com.example:core", "com.example:app");
        assertThat(order.indexOf("com.example:core")).isLessThan(order.indexOf("com.example:app"));
    }
}
