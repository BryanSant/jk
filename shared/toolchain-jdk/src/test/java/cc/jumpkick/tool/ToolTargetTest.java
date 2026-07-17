// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolTargetTest {

    @Test
    void runnable_extensions_classify_as_files_even_when_missing() {
        assertThat(ToolTarget.classify("hello.java")).isInstanceOf(ToolTarget.RunnableFile.class);
        assertThat(ToolTarget.classify("script.kts")).isInstanceOf(ToolTarget.RunnableFile.class);
        assertThat(ToolTarget.classify("lib.kt")).isInstanceOf(ToolTarget.RunnableFile.class);
        assertThat(ToolTarget.classify("/nowhere/app.jar")).isInstanceOf(ToolTarget.RunnableFile.class);
    }

    @Test
    void existing_paths_beat_remote_interpretations(@TempDir Path tmp) throws Exception {
        Path weird = tmp.resolve("gh:weird-dir");
        Files.createDirectories(weird);
        assertThat(ToolTarget.classify(weird.toString())).isInstanceOf(ToolTarget.Directory.class);

        Path plain = tmp.resolve("notes.txt");
        Files.writeString(plain, "x");
        assertThat(ToolTarget.classify(plain.toString())).isInstanceOf(ToolTarget.UnsupportedFile.class);
    }

    @Test
    void git_syntax_classifies_as_git() {
        assertThat(ToolTarget.classify("git+https://example.com/x/y")).isInstanceOf(ToolTarget.Git.class);
        assertThat(ToolTarget.classify("git@github.com:acme/widgets.git")).isInstanceOf(ToolTarget.Git.class);
        assertThat(ToolTarget.classify("ssh://git@host/repo")).isInstanceOf(ToolTarget.Git.class);
        assertThat(ToolTarget.classify("gh:acme/widgets")).isInstanceOf(ToolTarget.Git.class);
        assertThat(ToolTarget.classify("https://example.com/x/y/thing.git")).isInstanceOf(ToolTarget.Git.class);
        // Forge repo roots are clones, not downloads — with or without an embedded ref.
        assertThat(ToolTarget.classify("https://github.com/acme/widgets")).isInstanceOf(ToolTarget.Git.class);
        assertThat(ToolTarget.classify("https://github.com/acme/widgets@v1.2.0"))
                .isInstanceOf(ToolTarget.Git.class);
    }

    @Test
    void non_repo_urls_classify_as_url() {
        assertThat(ToolTarget.classify("https://example.com/scripts/hello.sh")).isInstanceOf(ToolTarget.Url.class);
        assertThat(ToolTarget.classify("https://github.com/acme/widgets/blob/main/x.md"))
                .isInstanceOf(ToolTarget.Url.class);
        // A runnable extension does NOT make a remote target a local file.
        assertThat(ToolTarget.classify("https://example.com/tool.jar")).isInstanceOf(ToolTarget.Url.class);
        assertThat(ToolTarget.classify("https://example.com/raw/hello.java")).isInstanceOf(ToolTarget.Url.class);
    }

    @Test
    void coordinate_specs_classify_as_gav() {
        assertThat(ToolTarget.classify("com.example:widget-cli:1.0.0")).isInstanceOf(ToolTarget.Gav.class);
        assertThat(ToolTarget.classify("com.example:widget-cli")).isInstanceOf(ToolTarget.Gav.class);
        assertThat(ToolTarget.classify("com.example:widget-cli@^1.2")).isInstanceOf(ToolTarget.Gav.class);
    }

    @Test
    void name_at_suffix_uses_the_syntax_discriminators() {
        // Suffix with `/` or infix `~` → JBang catalog ref.
        assertThat(ToolTarget.classify("hello@user/repo")).isInstanceOf(ToolTarget.JBangAlias.class);
        assertThat(ToolTarget.classify("hello@acme~experimental")).isInstanceOf(ToolTarget.JBangAlias.class);
        // A colon after the `@` is a catalog host:port, not a coordinate.
        assertThat(ToolTarget.classify("hello@127.0.0.1:8080/cat")).isInstanceOf(ToolTarget.JBangAlias.class);
        // Leading `~` is a selector, not a catalog path.
        var tilde = ToolTarget.classify("hibernate@~7.0.0");
        assertThat(tilde).isInstanceOf(ToolTarget.CatalogName.class);
        assertThat(((ToolTarget.CatalogName) tilde).suffix()).isEqualTo("~7.0.0");
        // Bare-word suffix stays a catalog name; the lookup disambiguates.
        var bare = ToolTarget.classify("myapp@release12");
        assertThat(bare).isInstanceOf(ToolTarget.CatalogName.class);
        assertThat(((ToolTarget.CatalogName) bare).name()).isEqualTo("myapp");
        assertThat(((ToolTarget.CatalogName) bare).suffix()).isEqualTo("release12");
    }

    @Test
    void bare_words_are_catalog_names() {
        var t = ToolTarget.classify("ktlint");
        assertThat(t).isInstanceOf(ToolTarget.CatalogName.class);
        assertThat(((ToolTarget.CatalogName) t).name()).isEqualTo("ktlint");
        assertThat(((ToolTarget.CatalogName) t).suffix()).isNull();
    }
}
