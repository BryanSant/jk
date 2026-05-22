// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.GitRefSpec;
import dev.buildjk.model.Scope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildJkParserGitTest {

    @Test
    void parses_a_tag_form_git_dependency() {
        BuildJk model = BuildJkParser.parse("""
                project {
                  group    = "com.example"
                  artifact = "widget"
                  version  = "1.0.0"
                  jdk      = "21"
                }

                dependencies.main {
                  "com.foo:bar" = { git = "https://github.com/foo/bar", tag = "v1.2.3" }
                }
                """);
        Dependency dep = model.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().canonicalUrl()).isEqualTo("https://github.com/foo/bar");
        assertThat(dep.gitSource().originalUrl()).isEqualTo("https://github.com/foo/bar");
        assertThat(dep.gitSource().ref()).isInstanceOf(GitRefSpec.Tag.class);
        assertThat(((GitRefSpec.Tag) dep.gitSource().ref()).name()).isEqualTo("v1.2.3");
        assertThat(dep.gitSource().submodules()).isTrue();
        assertThat(dep.gitSource().verifySignature()).isFalse();
    }

    @Test
    void parses_shorthand_url_with_branch_and_path() {
        BuildJk model = BuildJkParser.parse("""
                project { group = "g", artifact = "a", version = "1", jdk = "21" }
                dependencies.main {
                  "com.foo:baz" = {
                    git = "gh:foo/baz"
                    branch = "main"
                    path = "modules/baz"
                    submodules = false
                  }
                }
                """);
        Dependency dep = model.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.gitSource().canonicalUrl()).isEqualTo("https://github.com/foo/baz");
        assertThat(dep.gitSource().originalUrl()).isEqualTo("gh:foo/baz");
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Branch("main"));
        assertThat(dep.gitSource().path()).isEqualTo("modules/baz");
        assertThat(dep.gitSource().submodules()).isFalse();
    }

    @Test
    void rev_pin_with_verify_signed_flag() {
        BuildJk model = BuildJkParser.parse("""
                project { group = "g", artifact = "a", version = "1", jdk = "21" }
                dependencies.main {
                  "com.foo:qux" = {
                    git = "git@github.com:foo/qux.git"
                    rev = "0123456789abcdef0123456789abcdef01234567"
                    verify-signed = true
                  }
                }
                """);
        Dependency dep = model.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.gitSource().canonicalUrl()).isEqualTo("ssh://git@github.com/foo/qux");
        assertThat(dep.gitSource().ref()).isEqualTo(
                new GitRefSpec.Rev("0123456789abcdef0123456789abcdef01234567"));
        assertThat(dep.gitSource().verifySignature()).isTrue();
    }

    @Test
    void rejects_missing_ref_spec() {
        assertThatThrownBy(() -> BuildJkParser.parse("""
                project { group = "g", artifact = "a", version = "1", jdk = "21" }
                dependencies.main {
                  "com.foo:bar" = { git = "https://github.com/foo/bar" }
                }
                """))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("must set one of `tag`, `branch`, or `rev`");
    }

    @Test
    void rejects_conflicting_ref_specs() {
        assertThatThrownBy(() -> BuildJkParser.parse("""
                project { group = "g", artifact = "a", version = "1", jdk = "21" }
                dependencies.main {
                  "com.foo:bar" = { git = "https://github.com/foo/bar",
                                    tag = "v1", branch = "main" }
                }
                """))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void non_git_dependency_still_parses_as_maven_coord() {
        BuildJk model = BuildJkParser.parse("""
                project { group = "g", artifact = "a", version = "1", jdk = "21" }
                dependencies.main {
                  "com.example:lib" = "1.0.0"
                }
                """);
        Dependency dep = model.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isFalse();
        assertThat(dep.gitSource()).isNull();
    }
}
