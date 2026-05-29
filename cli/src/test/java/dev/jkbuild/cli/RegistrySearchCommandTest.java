// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrySearchCommandTest {

    private ByteArrayOutputStream out;
    private PrintStream originalOut;

    @BeforeEach
    void capture() {
        out = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() { System.setOut(originalOut); }

    @Test
    void search_matches_substring_of_name_in_bundled_registry() {
        int exit = new CommandLine(new Jk()).execute("registry", "search", "junit");
        assertThat(exit).isZero();
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("junit-jupiter");
        assertThat(stdout).contains("junit-platform-launcher");
        // Layer tag is present.
        assertThat(stdout).contains("[bundled]");
    }

    @Test
    void search_matches_substring_of_group() {
        // "springframework" appears in groups (org.springframework.boot) but
        // not in names — exercises the group-field branch of the matcher.
        int exit = new CommandLine(new Jk()).execute("registry", "search", "springframework");
        assertThat(exit).isZero();
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("spring-boot-starter");
        assertThat(stdout).contains("org.springframework.boot");
    }

    @Test
    void search_AND_semantics_with_multiple_terms() {
        // Both "spring" and "starter" must appear somewhere.
        int exit = new CommandLine(new Jk()).execute(
                "registry", "search", "spring", "starter");
        assertThat(exit).isZero();
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("spring-boot-starter");
        // But NOT something that has spring but no starter — there is no
        // such bundled entry, so this is implicit.
    }

    @Test
    void search_is_case_insensitive() {
        int exit = new CommandLine(new Jk()).execute("registry", "search", "PICOCLI");
        assertThat(exit).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("picocli");
    }

    @Test
    void search_with_no_matches_returns_nonzero_and_clear_message() {
        int exit = new CommandLine(new Jk()).execute(
                "registry", "search", "definitely-not-in-registry-xyz");
        assertThat(exit).isOne();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("No matches");
    }

    @Test
    void search_limit_truncates_with_explanatory_footer() {
        int exit = new CommandLine(new Jk()).execute(
                "registry", "search", "kotlin", "--limit", "1");
        assertThat(exit).isZero();
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("more");
    }
}
