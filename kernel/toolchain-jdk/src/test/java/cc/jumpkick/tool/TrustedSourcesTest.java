// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustedSourcesTest {

    @Test
    void add_list_remove_round_trip(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        assertThat(t.list()).isEmpty();
        assertThat(t.add("https://github.com/acme/")).isTrue();
        assertThat(t.add("https://github.com/acme/")).isFalse(); // dedup

        TrustedSources reloaded = TrustedSources.load(state);
        assertThat(reloaded.list()).containsExactly("https://github.com/acme/");
        assertThat(reloaded.remove("https://github.com/acme/")).isTrue();
        assertThat(TrustedSources.load(state).list()).isEmpty();
    }

    @Test
    void prefix_matching_is_jbang_style(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        t.add("https://github.com/acme/");
        assertThat(t.isTrusted("https://github.com/acme/widgets/blob/main/x.java")).isTrue();
        assertThat(t.isTrusted("HTTPS://GITHUB.COM/acme/x.java")).isTrue(); // scheme+host case-folded
        assertThat(t.isTrusted("https://github.com/other/x.java")).isFalse();
        assertThat(t.isTrusted("https://github.com/ACME/x.java")).isFalse(); // path stays case-sensitive
        assertThat(t.isTrusted("https://example.com/acme/x.java")).isFalse();
    }

    @Test
    void suggested_prefix_is_host_plus_first_segment() {
        assertThat(TrustedSources.suggestedPrefix("https://github.com/acme/widgets/blob/main/x.java"))
                .isEqualTo("https://github.com/acme/");
        assertThat(TrustedSources.suggestedPrefix("http://127.0.0.1:8080/scripts/x.java"))
                .isEqualTo("http://127.0.0.1:8080/scripts/");
        assertThat(TrustedSources.suggestedPrefix("https://example.com/")).isEqualTo("https://example.com/");
    }

    @Test
    void parses_jbang_trusted_sources_json_with_comments() {
        String json = """
                [
                  // added 2024-01-01
                  "https://github.com/jbangdev/",
                  "https://gist.github.com/maxandersen/"
                ]
                """;
        assertThat(TrustedSources.parseJBang(json))
                .containsExactly("https://github.com/jbangdev/", "https://gist.github.com/maxandersen/");
    }

    @Test
    void hand_edited_toml_loads(@TempDir Path state) throws Exception {
        Files.writeString(state.resolve("trusted-sources.toml"), """
                sources = [
                  "https://acme.dev/",
                ]
                """);
        assertThat(TrustedSources.load(state).isTrusted("https://acme.dev/tool.jar")).isTrue();
    }

    @Test
    void single_line_array_and_ipv6_brackets_load(@TempDir Path state) throws Exception {
        Files.writeString(state.resolve("trusted-sources.toml"), """
                sources = ["https://[::1]:8443/tools/", "https://acme.dev/"]
                """);
        var trusted = TrustedSources.load(state);
        assertThat(trusted.list())
                .containsExactly("https://[::1]:8443/tools/", "https://acme.dev/");
        assertThat(trusted.isTrusted("https://acme.dev/x.jar")).isTrue();
    }
}
