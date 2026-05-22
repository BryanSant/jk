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

class ProvenanceTest {

    @Test
    void single_path() {
        BuildJk project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:leaf");
        assertThat(paths).singleElement().satisfies(p ->
                assertThat(p.render()).isEqualTo("com.foo:root v1.0 -> com.foo:leaf v1.0"));
    }

    @Test
    void multiple_paths_for_diamond() {
        // root -> a -> leaf
        //      -> b -> leaf
        BuildJk project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:a@1.0", "com.foo:b@1.0")),
                pkg("com.foo:a", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:b", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:leaf");
        assertThat(paths).extracting(Provenance.Path::render)
                .containsExactlyInAnyOrder(
                        "com.foo:root v1.0 -> com.foo:a v1.0 -> com.foo:leaf v1.0",
                        "com.foo:root v1.0 -> com.foo:b v1.0 -> com.foo:leaf v1.0");
    }

    @Test
    void returns_empty_when_target_absent() {
        BuildJk project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(pkg("com.foo:root", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.missing:thing");
        assertThat(paths).isEmpty();
    }

    @Test
    void target_is_a_declared_root_yields_single_step() {
        BuildJk project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(pkg("com.foo:root", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:root");
        assertThat(paths).singleElement().satisfies(p ->
                assertThat(p.render()).isEqualTo("com.foo:root v1.0"));
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
