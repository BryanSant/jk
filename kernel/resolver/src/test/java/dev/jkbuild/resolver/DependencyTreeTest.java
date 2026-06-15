// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyTreeTest {

    @Test
    void renders_simple_tree() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        String rendered = DependencyTree.render(project, lock);
        assertThat(rendered).isEqualToIgnoringWhitespace("""
                com.example:widget:0.1.0
                ╰── com.foo:root:1.0
                    ╰── com.foo:leaf:1.0
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
    void depth_zero_shows_only_roots() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

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
    void composite_deps_are_annotated_not_missing(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        var deps = new ArrayList<Dependency>();
        deps.add(Dependency.path("lib", "com.foo:lib", "../lib"));          // ../lib absent → not built
        deps.add(Dependency.git("com.foo:forked", dev.jkbuild.model.GitSource.of(
                "https://x/forked", "https://x/forked", new dev.jkbuild.model.GitRefSpec.Branch("main"))));
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.MAIN, deps)));

        String rendered = DependencyTree.render(project, lockOf(), tmp,
                Integer.MAX_VALUE, DependencyTree.Styling.plain());

        assertThat(rendered).contains("com.foo:lib [path, not built]");
        assertThat(rendered).contains("com.foo:forked [git: main]");
        assertThat(rendered).doesNotContain("(missing)");   // composite deps aren't "missing"
    }

    // --- helpers -----------------------------------------------------------

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
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test",
                Lockfile.RESOLUTION_ALGORITHM, List.of(packages));
    }

    private static Lockfile.Artifact pkg(String module, String version, List<String> deps) {
        return new Lockfile.Artifact(module, version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:dummy", null, deps);
    }
}
