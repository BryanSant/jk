// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitUrlTest {

    @Test
    void expand_translates_known_host_shorthands() {
        assertThat(GitUrl.expand("gh:foo/bar")).isEqualTo("https://github.com/foo/bar");
        assertThat(GitUrl.expand("gl:foo/bar")).isEqualTo("https://gitlab.com/foo/bar");
        assertThat(GitUrl.expand("bb:foo/bar")).isEqualTo("https://bitbucket.org/foo/bar");
        assertThat(GitUrl.expand("sr:~foo/bar")).isEqualTo("https://git.sr.ht/~foo/bar");
    }

    @Test
    void expand_passes_through_full_urls_untouched() {
        assertThat(GitUrl.expand("https://example.com/foo/bar"))
                .isEqualTo("https://example.com/foo/bar");
    }

    @Test
    void canonicalize_strips_dot_git_and_default_port_and_trailing_slash() {
        assertThat(GitUrl.canonicalize("https://GitHub.com:443/Foo/Bar.git/"))
                .isEqualTo("https://github.com/Foo/Bar");
    }

    @Test
    void canonicalize_keeps_non_default_port() {
        assertThat(GitUrl.canonicalize("https://example.com:8443/foo/bar"))
                .isEqualTo("https://example.com:8443/foo/bar");
    }

    @Test
    void canonicalize_scp_form_becomes_ssh_url() {
        assertThat(GitUrl.canonicalize("git@github.com:foo/bar.git"))
                .isEqualTo("ssh://git@github.com/foo/bar");
    }

    @Test
    void canonicalize_treats_shorthand_with_dot_git_identically() {
        // gh:foo/bar and gh:foo/bar.git collapse to the same cache key.
        assertThat(GitUrl.canonicalize("gh:foo/bar"))
                .isEqualTo(GitUrl.canonicalize("gh:foo/bar.git"));
    }

    @Test
    void canonical_hash_is_stable_and_differs_across_repos() {
        String a = GitUrl.canonicalHash("gh:foo/bar");
        String b = GitUrl.canonicalHash("gh:foo/baz");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo(GitUrl.canonicalHash("gh:foo/bar.git"));
        assertThat(a).hasSize(64);
    }
}
