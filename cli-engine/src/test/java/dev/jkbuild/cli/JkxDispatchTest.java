// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The argv[0] `jkx` → `jk tool run` expansion (Jk.rewriteForProgramName + Argv0.baseName). */
class JkxDispatchTest {

    @Test
    void jkx_program_name_prepends_tool_run() {
        String[] out = Jk.rewriteForProgramName(new String[] {"ktlint", "--", "src/"}, "jkx");
        assertThat(out).containsExactly("tool", "run", "ktlint", "--", "src/");
    }

    @Test
    void jkx_with_no_args_still_expands() {
        assertThat(Jk.rewriteForProgramName(new String[] {}, "jkx")).containsExactly("tool", "run");
    }

    @Test
    void jkx_help_becomes_tool_run_help() {
        assertThat(Jk.rewriteForProgramName(new String[] {"--help"}, "jkx"))
                .containsExactly("tool", "run", "--help");
    }

    @Test
    void other_program_names_pass_through_untouched() {
        String[] in = {"build", "-C", "/tmp"};
        assertThat(Jk.rewriteForProgramName(in, "jk")).isSameAs(in);
        assertThat(Jk.rewriteForProgramName(in, null)).isSameAs(in);
        assertThat(Jk.rewriteForProgramName(in, "java")).isSameAs(in);
    }

    @Test
    void basename_strips_directories_case_and_exe() {
        assertThat(Argv0.baseName("/home/user/.jk/bin/jkx")).isEqualTo("jkx");
        assertThat(Argv0.baseName("jkx")).isEqualTo("jkx");
        assertThat(Argv0.baseName("C:\\Users\\u\\.jk\\bin\\jkx.exe")).isEqualTo("jkx");
        assertThat(Argv0.baseName("JKX.EXE")).isEqualTo("jkx");
        assertThat(Argv0.baseName("/usr/local/bin/jk")).isEqualTo("jk");
        assertThat(Argv0.baseName("")).isNull();
        assertThat(Argv0.baseName(null)).isNull();
    }

    @Test
    void override_property_wins_over_detection() {
        System.setProperty(Argv0.OVERRIDE_PROPERTY, "/tmp/whatever/jkx");
        try {
            assertThat(Argv0.programName()).isEqualTo("jkx");
        } finally {
            System.clearProperty(Argv0.OVERRIDE_PROPERTY);
        }
    }
}
