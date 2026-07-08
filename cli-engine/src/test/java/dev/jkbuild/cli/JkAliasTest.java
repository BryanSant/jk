// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JkAliasTest {

    @Test
    void rewrite_maps_known_aliases_to_canonical_verbs() {
        assertThat(Jk.rewriteAlias(new String[] {"generate"})[0]).isEqualTo("new");
        assertThat(Jk.rewriteAlias(new String[] {"dependencies"})[0]).isEqualTo("tree");
        assertThat(Jk.rewriteAlias(new String[] {"package"})[0]).isEqualTo("build");
        assertThat(Jk.rewriteAlias(new String[] {"deploy"})[0]).isEqualTo("publish");
        assertThat(Jk.rewriteAlias(new String[] {"upgrade"})[0]).isEqualTo("update");
        assertThat(Jk.rewriteAlias(new String[] {"sh"})[0]).isEqualTo("shell");
        assertThat(Jk.rewriteAlias(new String[] {"bash"})[0]).isEqualTo("shell");
        assertThat(Jk.rewriteAlias(new String[] {"nativeCompile"})[0]).isEqualTo("native");
        assertThat(Jk.rewriteAlias(new String[] {"verify-target"})[0]).isEqualTo("verify");
        assertThat(Jk.rewriteAlias(new String[] {"verify-build"})[0]).isEqualTo("verify");
        assertThat(Jk.rewriteAlias(new String[] {"check"})[0]).isEqualTo("compile");
        assertThat(Jk.rewriteAlias(new String[] {"why-rebuilt"})[0]).isEqualTo("explain");
    }

    @Test
    void rewrite_only_touches_the_first_positional() {
        // "package" is an alias only as the first positional. Later occurrences pass through.
        String[] in = {"add", "package", "com.example:foo:1.0"};
        String[] out = Jk.rewriteAlias(in);
        assertThat(out).containsExactly("add", "package", "com.example:foo:1.0");
    }

    @Test
    void rewrite_leaves_non_alias_args_alone() {
        String[] in = {"build", "-C", "/tmp"};
        assertThat(Jk.rewriteAlias(in)).isSameAs(in);
    }

    @Test
    void rewrite_handles_empty_argv() {
        String[] empty = {};
        assertThat(Jk.rewriteAlias(empty)).isSameAs(empty);
    }

    @Test
    void help_screen_does_not_list_any_aliases() {
        // Verbs in --help are listed under one or more "<Section> commands:"
        // headings. Within a section, each row is "  <name>   <description>".
        List<String> verbNames = new ArrayList<>();
        boolean inSection = false;
        for (String raw : renderHelp().split("\\R")) {
            // Section headings are rendered in bright green; strip SGR codes before parsing.
            String line = raw.replaceAll("\\u001b\\[[0-9;]*m", "");
            if (line.matches("[A-Z].* commands:")) {
                inSection = true;
                continue;
            }
            if (line.isBlank()) {
                inSection = false;
                continue;
            }
            if (inSection && line.startsWith("  ")) {
                verbNames.add(line.trim().split("\\s+", 2)[0]);
            }
        }

        assertThat(verbNames).contains("compile");
        for (String alias : Jk.VERB_ALIASES.keySet()) {
            assertThat(verbNames).as("alias %s leaked into --help", alias).doesNotContain(alias);
        }
    }

    private static String renderHelp() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(buf));
        try {
            Jk.execute("--help");
        } finally {
            System.setOut(orig);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
