// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class DependencyTreeTest {

    @Test
    void renders_simple_tree() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock =
                lockOf(pkg("com.foo:root", "1.0", List.of("com.foo:leaf@1.0")), pkg("com.foo:leaf", "1.0", List.of()));

        String rendered = DependencyTree.render(project, lock);
        assertThat(rendered).isEqualToIgnoringWhitespace("""
                com.example:widget:0.1.0
                ╰─ com.foo:root:1.0
                    ╰─ com.foo:leaf:1.0
                """);
    }

    @Test
    void marks_diamond_repeats_with_asterisk() {
        // root -> a -> leaf
        //      -> b -> leaf
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:a@1.0", "com.foo:b@1.0")),
                pkg("com.foo:a", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:b", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        String rendered = DependencyTree.render(project, lock);
        // First occurrence: full label. Second: ⎋ marker.
        assertThat(rendered).contains("com.foo:leaf:1.0\n");
        assertThat(rendered).contains("com.foo:leaf:1.0 ⎋\n");
    }

    @Test
    void back_reference_rows_are_styled_as_a_whole_unit() {
        // root -> a -> leaf ; root -> b -> leaf  (leaf revisited under b)
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:a@1.0", "com.foo:b@1.0")),
                pkg("com.foo:a", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:b", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));
        var styling = new DependencyTree.Styling(
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                s -> "<dim>" + s + "</dim>", // reference styler
                UnaryOperator.identity()); // scope badge

        String rendered = DependencyTree.render(project, lock, Integer.MAX_VALUE, styling);

        // The revisited leaf is a back-reference: connector + coord + ⎋ wrapped as ONE unit.
        assertThat(rendered).contains("com.foo:leaf:1.0 ⎋</dim>");
        assertThat(rendered).contains("<dim>");
        // The first leaf occurrence is a real node — not wrapped by the reference styler.
        assertThat(rendered).contains("─ com.foo:leaf:1.0\n");
    }

    @Test
    void depth_zero_shows_only_roots() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock =
                lockOf(pkg("com.foo:root", "1.0", List.of("com.foo:leaf@1.0")), pkg("com.foo:leaf", "1.0", List.of()));

        String rendered = DependencyTree.render(project, lock, 0);
        assertThat(rendered).contains("com.foo:root:1.0");
        assertThat(rendered).doesNotContain("leaf");
    }

    @Test
    void missing_lockfile_entry_marked_as_missing() {
        JkBuild project = projectWithMainDeps("com.foo:not-locked");
        Lockfile lock = lockOf();

        String rendered = DependencyTree.render(project, lock);
        assertThat(rendered).contains("com.foo:not-locked (missing)");
    }

    @Test
    void branch_git_dep_renders_as_a_locked_coordinate(@org.junit.jupiter.api.io.TempDir Path tmp) {
        // A branch-ref git dep is materialized and pinned in jk.lock like any other
        // git dep — it renders exactly like a locked Maven coordinate, no special tag.
        var deps = new ArrayList<Dependency>();
        deps.add(Dependency.git(
                "com.foo:forked",
                dev.jkbuild.model.GitSource.of(
                        "https://x/forked", "https://x/forked", new dev.jkbuild.model.GitRefSpec.Branch("main"))));
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.MAIN, deps)));
        Lockfile lock = lockOf(pkg("com.foo:forked", "main-SNAPSHOT", List.of()));

        String rendered =
                DependencyTree.render(project, lock, tmp, Integer.MAX_VALUE, DependencyTree.Styling.plain());

        assertThat(rendered).contains("com.foo:forked:main-SNAPSHOT");
        assertThat(rendered).doesNotContain("[git:");
        assertThat(rendered).doesNotContain("(missing)");
    }

    @Test
    void branch_git_dep_missing_from_lock_is_marked_missing(@org.junit.jupiter.api.io.TempDir Path tmp) {
        var deps = new ArrayList<Dependency>();
        deps.add(Dependency.git(
                "com.foo:forked",
                dev.jkbuild.model.GitSource.of(
                        "https://x/forked", "https://x/forked", new dev.jkbuild.model.GitRefSpec.Branch("main"))));
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.MAIN, deps)));

        String rendered =
                DependencyTree.render(project, lockOf(), tmp, Integer.MAX_VALUE, DependencyTree.Styling.plain());

        assertThat(rendered).contains("com.foo:forked (missing)");
    }

    @Test
    void direct_deps_are_grouped_into_scope_sections(@org.junit.jupiter.api.io.TempDir Path tmp) {
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(
                        Scope.MAIN, List.of(new Dependency("com.foo:lib", new VersionSelector.Exact("=1.0", "1.0"))),
                        Scope.TEST,
                                List.of(new Dependency("org.junit:junit", new VersionSelector.Exact("=5.0", "5.0"))))));
        Lockfile lock = lockOf(pkg("com.foo:lib", "1.0", List.of()), pkg("org.junit:junit", "5.0", List.of()));

        String rendered = DependencyTree.render(project, lock, tmp, Integer.MAX_VALUE, DependencyTree.Styling.plain());

        // main + test sections present; empty scopes (provided, runtime, …) omitted.
        // (Plain styling emits the bare scope label; padding/caps are the styler's job.)
        assertThat(rendered).contains("main").contains("test");
        assertThat(rendered).doesNotContain("provided").doesNotContain("runtime");
        // Each dep lands under its own scope; main before test.
        assertThat(rendered.indexOf("main")).isLessThan(rendered.indexOf("com.foo:lib"));
        assertThat(rendered.indexOf("com.foo:lib")).isLessThan(rendered.indexOf("test"));
        assertThat(rendered.indexOf("test")).isLessThan(rendered.indexOf("org.junit:junit"));
    }

    @Test
    void workspace_root_groups_modules_under_scope_sections(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        // A workspace root with two modules; module b depends on a via `workspace = true`.
        Files.writeString(root.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "ws"
                version = "9.9.9"

                [workspace]
                modules = ["a", "b"]
                """);
        Path a = Files.createDirectories(root.resolve("a"));
        Files.writeString(a.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "a"
                version = "9.9.9"
                """);
        Files.writeString(a.resolve("jk.lock"), EMPTY_LOCK);
        Path b = Files.createDirectories(root.resolve("b"));
        Files.writeString(b.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "b"
                version = "9.9.9"

                [dependencies]
                a = { workspace = true }
                """);
        Files.writeString(b.resolve("jk.lock"), EMPTY_LOCK);

        JkBuild rootProject = dev.jkbuild.config.JkBuildParser.parse(root.resolve("jk.toml"));
        String rendered =
                DependencyTree.render(rootProject, lockOf(), root, Integer.MAX_VALUE, DependencyTree.Styling.plain());

        assertThat(rendered).contains("com.acme:ws:9.9.9"); // root
        // Scope-first: a `main` section is the top-level node, and module b (the only
        // module that declares a main dep) is a node beneath it. Module a has no deps
        // of its own, so it is NOT a standalone node — it appears only as b's sibling.
        assertThat(rendered).contains("main");
        assertThat(rendered).contains("com.acme:b:9.9.9");
        // b's `workspace = true` dep on a: collapsed reference, resolved to a's real
        // coord (not the synthetic "workspace:a") and not flagged "(missing)".
        assertThat(rendered).contains("com.acme:a [workspace]");
        assertThat(rendered).doesNotContain("workspace:a");
        assertThat(rendered).doesNotContain("(missing)");
        // b is the group node; a appears only as b's collapsed sibling nested under it.
        assertThat(rendered.indexOf("com.acme:b:9.9.9")).isLessThan(rendered.indexOf("com.acme:a [workspace]"));
    }

    @Test
    void flatten_lists_each_scope_dep_once_without_nesting(@org.junit.jupiter.api.io.TempDir Path dir) {
        // Diamond: root -> a -> leaf ; root -> b -> leaf.
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:a@1.0", "com.foo:b@1.0")),
                pkg("com.foo:a", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:b", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        String rendered =
                DependencyTree.render(project, lock, dir, Integer.MAX_VALUE, DependencyTree.Styling.plain(), true);

        // Whole closure present, flat, with no back-reference markers.
        assertThat(rendered)
                .contains("com.foo:root:1.0")
                .contains("com.foo:a:1.0")
                .contains("com.foo:b:1.0")
                .contains("com.foo:leaf:1.0");
        assertThat(rendered).doesNotContain("⎋");
        // leaf is deduped to a single line despite two paths to it.
        int first = rendered.indexOf("com.foo:leaf:1.0");
        assertThat(rendered.indexOf("com.foo:leaf:1.0", first + 1)).isEqualTo(-1);
    }

    @Test
    void explicit_scope_order_filters_and_reorders_sections(@org.junit.jupiter.api.io.TempDir Path dir) {
        var main = List.of(new Dependency("com.foo:m", new VersionSelector.Exact("=1.0", "1.0")));
        var test = List.of(new Dependency("com.foo:t", new VersionSelector.Exact("=1.0", "1.0")));
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.MAIN, main, Scope.TEST, test)));
        Lockfile lock = lockOf(pkg("com.foo:m", "1.0", List.of()), pkg("com.foo:t", "1.0", List.of()));

        String rendered = DependencyTree.render(
                project,
                lock,
                dir,
                Integer.MAX_VALUE,
                DependencyTree.Styling.plain(),
                false,
                List.of(Scope.TEST, Scope.MAIN));

        // Both requested sections render, in the given order (test before main),
        // overriding the default ordering; unrequested scopes are omitted.
        assertThat(rendered).contains("test").contains("main").doesNotContain("provided");
        assertThat(rendered.indexOf("test")).isLessThan(rendered.indexOf("main"));
    }

    @Test
    void stack_blends_all_scopes_under_one_badge_row(@org.junit.jupiter.api.io.TempDir Path dir) {
        var main = List.of(new Dependency("com.foo:m", new VersionSelector.Exact("=1.0", "1.0")));
        var test = List.of(new Dependency("com.foo:t", new VersionSelector.Exact("=1.0", "1.0")));
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.MAIN, main, Scope.TEST, test)));
        Lockfile lock = lockOf(pkg("com.foo:m", "1.0", List.of()), pkg("com.foo:t", "1.0", List.of()));

        String rendered = DependencyTree.render(
                project, lock, dir, Integer.MAX_VALUE, DependencyTree.Styling.plain(), false, null, true);

        // A single header line carries every scope badge; deps from all scopes are
        // blended into the one tree beneath it.
        List<String> headers = Arrays.stream(rendered.split("\n"))
                .filter(l -> l.contains("main"))
                .toList();
        assertThat(headers).hasSize(1);
        assertThat(headers.get(0)).contains("main").contains("test");
        assertThat(rendered).contains("com.foo:m:1.0").contains("com.foo:t:1.0");
    }

    // --- helpers -----------------------------------------------------------

    private static final String EMPTY_LOCK = """
            version = 1
            generated-by = "jk test"
            resolution-algorithm = "pubgrub-v1"
            """;

    private static JkBuild projectWithMainDeps(String... modules) {
        var deps = new ArrayList<Dependency>();
        for (String m : modules) {
            deps.add(new Dependency(m, new VersionSelector.Exact("=1.0", "1.0")));
        }
        return new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.MAIN, deps)));
    }

    private static Lockfile lockOf(Lockfile.Artifact... packages) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(packages));
    }

    private static Lockfile.Artifact pkg(String module, String version, List<String> deps) {
        return new Lockfile.Artifact(
                module, version, "central+https://repo.maven.apache.org/maven2/", "sha256:dummy", null, deps);
    }
}
