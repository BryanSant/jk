// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Graph-resolution coverage for {@link BuildGraph}: workspace-module topo-sort by sibling
 * dependency order. There is no separate "composite dependency" unit anymore — a local sibling is
 * always a workspace module, and a git dependency (any ref type) is a lock-pinned coordinate, not
 * a build unit here.
 */
class BuildGraphTest {

    private static BuildGraph.Result resolve(Path entryDir) throws IOException {
        JkBuild entry = JkBuildParser.parse(Files.readString(entryDir.resolve("jk.toml")));
        return BuildGraph.resolve(entryDir, entry);
    }

    private static List<String> coords(BuildGraph.Result r) {
        return r.topoOrder().stream().map(BuildGraph.BuildUnit::coord).toList();
    }

    @Test
    void standalone_project_is_a_single_root_unit(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                jdk     = 21
                java    = 21
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        assertThat(coords(r)).containsExactly("com.example:app");
    }

    @Test
    void workspace_modules_become_units_in_dependency_order(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 21
                java = 21

                [workspace]
                modules = ["core", "app"]
                """);
        Files.createDirectories(tmp.resolve("core"));
        Files.writeString(tmp.resolve("core/jk.toml"), """
                [project]
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                jdk = 21
                java = 21
                """);
        // app depends on core (sibling), so core builds first.
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app/jk.toml"), """
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 21
                java = 21

                [dependencies]
                core = { group = "com.example", name = "core", version = "1.0.0" }
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        List<String> order = coords(r);
        assertThat(order).containsExactlyInAnyOrder("com.example:core", "com.example:app");
        assertThat(order.indexOf("com.example:core")).isLessThan(order.indexOf("com.example:app"));
    }

    @Test
    void coordinator_workspace_root_without_sources_is_not_a_unit(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 21
                java = 21

                [workspace]
                modules = ["core"]
                """);
        Files.createDirectories(tmp.resolve("core/src"));
        Files.writeString(tmp.resolve("core/src/Main.java"), "class Main {}");
        Files.writeString(tmp.resolve("core/jk.toml"), """
                [project]
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                jdk = 21
                java = 21
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        // Root has no src/ → coordinator only; only the module is a unit.
        assertThat(coords(r)).containsExactly("com.example:core");
    }

    @Test
    void self_buildable_workspace_root_is_also_a_unit(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 21
                java = 21

                [workspace]
                modules = ["core"]
                """);
        // Root carries its own sources → it is itself a build unit.
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        Files.createDirectories(tmp.resolve("core/src"));
        Files.writeString(tmp.resolve("core/src/Core.java"), "class Core {}");
        Files.writeString(tmp.resolve("core/jk.toml"), """
                [project]
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                jdk = 21
                java = 21
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        assertThat(coords(r)).containsExactlyInAnyOrder("com.example:root", "com.example:core");
    }

    @Test
    void module_dependency_cycle_is_reported(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 21
                java = 21

                [workspace]
                modules = ["a", "b"]
                """);
        Files.createDirectories(tmp.resolve("a"));
        Files.writeString(tmp.resolve("a/jk.toml"), """
                [project]
                group = "com.example"
                name  = "a"
                version = "1.0.0"
                jdk = 21
                java = 21

                [dependencies]
                b = { group = "com.example", name = "b", version = "1.0.0" }
                """);
        Files.createDirectories(tmp.resolve("b"));
        Files.writeString(tmp.resolve("b/jk.toml"), """
                [project]
                group = "com.example"
                name  = "b"
                version = "1.0.0"
                jdk = 21
                java = 21

                [dependencies]
                a = { group = "com.example", name = "a", version = "1.0.0" }
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).anyMatch(e -> e.contains("cycle"));
        assertThat(r.topoOrder()).isEmpty();
    }
}
