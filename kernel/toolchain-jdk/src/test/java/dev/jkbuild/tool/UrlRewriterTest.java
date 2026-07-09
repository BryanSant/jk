// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlRewriterTest {

    @Test
    void github_blob_rewrites_to_raw() {
        assertThat(UrlRewriter.rewrite("https://github.com/acme/widgets/blob/main/src/Hello.java"))
                .isEqualTo("https://raw.githubusercontent.com/acme/widgets/main/src/Hello.java");
    }

    @Test
    void gitlab_blob_rewrites_to_raw() {
        assertThat(UrlRewriter.rewrite("https://gitlab.com/acme/widgets/-/blob/main/Hello.java"))
                .isEqualTo("https://gitlab.com/acme/widgets/-/raw/main/Hello.java");
    }

    @Test
    void bitbucket_src_rewrites_to_raw() {
        assertThat(UrlRewriter.rewrite("https://bitbucket.org/acme/widgets/src/main/Hello.java"))
                .isEqualTo("https://bitbucket.org/acme/widgets/raw/main/Hello.java");
    }

    @Test
    void gist_page_rewrites_to_raw_endpoint() {
        assertThat(UrlRewriter.rewrite("https://gist.github.com/max/0123abcd"))
                .isEqualTo("https://gist.githubusercontent.com/max/0123abcd/raw");
    }

    @Test
    void plain_urls_pass_through() {
        assertThat(UrlRewriter.rewrite("https://example.com/x/Hello.java"))
                .isEqualTo("https://example.com/x/Hello.java");
        assertThat(UrlRewriter.rewrite("https://raw.githubusercontent.com/a/b/main/X.java"))
                .isEqualTo("https://raw.githubusercontent.com/a/b/main/X.java");
    }
}
