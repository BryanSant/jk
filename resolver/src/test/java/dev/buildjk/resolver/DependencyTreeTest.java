// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import dev.buildjk.lock.Lockfile;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyTreeTest {

    @Test
    void renders_simple_tree() {
        BuildJk project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        String rendered = DependencyTree.render(project, lock);
        assertThat(rendered).isEqualToIgnoringWhitespace("""
                widget v0.1.0
                └── com.foo:root v1.0
                    └── com.foo:leaf v1.0
                """);
    }

    @Test
    void marks_diamond_repeats_with_asterisk() {
        // root -> a -> leaf
        //      -> b -> leaf
        BuildJk project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:a@1.0", "com.foo:b@1.0")),
                pkg("com.foo:a", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:b", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        String rendered = DependencyTree.render(project, lock);
        // First occurrence: full label. Second: (*) marker.
        assertThat(rendered).contains("com.foo:leaf v1.0\n");
        assertThat(rendered).contains("com.foo:leaf v1.0 (*)\n");
    }

    @Test
    void depth_zero_shows_only_roots() {
        BuildJk project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        String rendered = DependencyTree.render(project, lock, 0);
        assertThat(rendered).contains("com.foo:root v1.0");
        assertThat(rendered).doesNotContain("leaf");
    }

    @Test
    void missing_lockfile_entry_marked_as_missing() {
        BuildJk project = projectWithMainDeps("com.foo:not-locked");
        Lockfile lock = lockOf();

        String rendered = DependencyTree.render(project, lock);
        assertThat(rendered).contains("com.foo:not-locked (missing)");
    }

    // --- helpers -----------------------------------------------------------

    private static BuildJk projectWithMainDeps(String... modules) {
        var deps = new java.util.ArrayList<Dependency>();
        for (String m : modules) {
            deps.add(new Dependency(m, new VersionSelector.Exact("=1.0", "1.0")));
        }
        return new BuildJk(
                new BuildJk.Project("com.example", "widget", "0.1.0", null),
                new BuildJk.Dependencies(Map.of(Scope.MAIN, deps)));
    }

    private static Lockfile lockOf(Lockfile.Package... packages) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test",
                Lockfile.RESOLUTION_ALGORITHM, List.of(packages));
    }

    private static Lockfile.Package pkg(String module, String version, List<String> deps) {
        return new Lockfile.Package(module, version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:dummy", null, deps);
    }
}
