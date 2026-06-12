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

class ProvenanceTest {

    @Test
    void single_path() {
        JkBuild project = projectWithMainDeps("com.foo:root");
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
        JkBuild project = projectWithMainDeps("com.foo:root");
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
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(pkg("com.foo:root", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.missing:thing");
        assertThat(paths).isEmpty();
    }

    @Test
    void target_is_a_declared_root_yields_single_step() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(pkg("com.foo:root", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:root");
        assertThat(paths).singleElement().satisfies(p ->
                assertThat(p.render()).isEqualTo("com.foo:root v1.0"));
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
