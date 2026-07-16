// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RepoSourceTest {

    @Test
    void name_and_url_split_on_first_plus() {
        RepoSource rs = RepoSource.parse("central+https://repo1.maven.org/maven2/");
        assertThat(rs.name()).isEqualTo("central");
        assertThat(rs.url()).isEqualTo("https://repo1.maven.org/maven2/");
    }

    @Test
    void split_uses_the_first_plus_only() {
        // A '+' inside the url must not confuse the split — only the first one delimits name/url.
        RepoSource rs = RepoSource.parse("central+https://host/a+b");
        assertThat(rs.name()).isEqualTo("central");
        assertThat(rs.url()).isEqualTo("https://host/a+b");
    }

    @Test
    void no_plus_yields_null_name_and_whole_string_url() {
        RepoSource rs = RepoSource.parse("local");
        assertThat(rs.name()).isNull();
        assertThat(rs.url()).isEqualTo("local"); // whole-string fallback (PolicyChecker contract)
    }

    @Test
    void leading_plus_yields_null_name_and_whole_string_url() {
        // plus == 0: name() null-rule (plus <= 0) AND url() fallback-rule (not plus > 0) both engage,
        // so name is null and url is the whole string — the two contracts agree here.
        RepoSource rs = RepoSource.parse("+https://host/");
        assertThat(rs.name()).isNull();
        assertThat(rs.url()).isEqualTo("+https://host/");
    }

    @Test
    void trailing_plus_is_where_the_two_contracts_diverge() {
        // plus == len-1: name() is null (plus >= len-1) but url() still splits (plus > 0) and yields
        // the empty tail. This byte-for-byte matches the original repoName / PolicyChecker behaviours.
        RepoSource rs = RepoSource.parse("central+");
        assertThat(rs.name()).isNull();
        assertThat(rs.url()).isEqualTo("");
    }

    @Test
    void git_source_has_no_plus_so_name_is_null_and_url_is_whole() {
        RepoSource rs = RepoSource.parse("git:gh:foo/bar:1.2.3");
        assertThat(rs.name()).isNull();
        assertThat(rs.url()).isEqualTo("git:gh:foo/bar:1.2.3");
    }

    @Test
    void null_source_is_tolerated() {
        // repoName(null) has always returned null; url() degrades to null rather than throwing.
        RepoSource rs = RepoSource.parse(null);
        assertThat(rs.name()).isNull();
        assertThat(rs.url()).isNull();
    }
}
