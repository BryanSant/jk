// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DependencyTest {

    @Test
    void path_dep_is_a_pinned_path_source_with_a_placeholder_module() {
        Dependency d = Dependency.pathByName("shared", new PathSource("../shared"));
        assertThat(d.isPath()).isTrue();
        assertThat(d.isGit()).isFalse();
        assertThat(d.isFile()).isFalse();
        assertThat(d.pathSource().rawPath()).isEqualTo("../shared");
        assertThat(d.module()).isEqualTo("path:shared");
        assertThat(d.pinned()).isTrue();
    }

    @Test
    void a_dependency_cannot_be_both_git_and_path() {
        GitSource git = GitSource.of("https://x/y", "https://x/y", new GitRefSpec.Tag("v1"));
        assertThatThrownBy(() -> new Dependency(
                "d", "g:a", VersionSelector.parse("=1"), git, null, true, false, new PathSource("../p")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void plain_coordinate_deps_carry_no_path_source() {
        Dependency d = Dependency.of("junit", "org.junit:junit", VersionSelector.parse("=5.0"));
        assertThat(d.isPath()).isFalse();
        assertThat(d.pathSource()).isNull();
    }
}
