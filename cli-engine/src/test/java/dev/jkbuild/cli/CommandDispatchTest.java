// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandDispatchTest {

    @Test
    void verbIndex_findsFirstPositional() {
        assertThat(CommandDispatch.verbIndex(List.of("build"))).isZero();
        assertThat(CommandDispatch.verbIndex(List.of("-q", "build"))).isEqualTo(1);
    }

    @Test
    void verbIndex_skipsValueTakingGlobalAndItsArgument() {
        // -C consumes the next token, so the verb is at index 2.
        assertThat(CommandDispatch.verbIndex(List.of("-C", "/tmp", "build"))).isEqualTo(2);
    }

    @Test
    void verbIndex_skipsAbbreviatedValueTakingGlobal() {
        // --dir is a unique prefix of the value-taking global --directory, so it consumes /tmp too.
        assertThat(CommandDispatch.verbIndex(List.of("--dir", "/tmp", "build"))).isEqualTo(2);
    }

    @Test
    void verbIndex_inlineValueGlobalDoesNotConsumeNext() {
        assertThat(CommandDispatch.verbIndex(List.of("--directory=/tmp", "build"))).isEqualTo(1);
    }

    @Test
    void verbIndex_doubleDashSelectsFollowingToken() {
        assertThat(CommandDispatch.verbIndex(List.of("--", "build"))).isEqualTo(1);
    }

    /**
     * ArgParser indexes option names last-wins and globals are merged after command options, so a
     * name collision silently replaces a command's option with the global (this once broke
     * `jk self update <version>`: the global -V/--version flag ate the value option). The
     * dispatcher now throws on collision; this walks every registered command so a new collision
     * fails here instead of in the field.
     */
    @Test
    void no_registered_command_option_collides_with_a_global() {
        java.util.Set<String> globals = new java.util.HashSet<>();
        for (var g : GlobalOptions.globalOpts()) globals.addAll(g.names());
        for (var cmd : CommandDispatch.commands()) assertNoGlobalCollision(cmd, cmd.name(), globals);
    }

    private static void assertNoGlobalCollision(
            dev.jkbuild.model.command.CliCommand cmd, String qualified, java.util.Set<String> globals) {
        for (var opt : cmd.options()) {
            for (String n : opt.names()) {
                assertThat(globals)
                        .as("`jk %s` declares %s, which the global options also declare", qualified, n)
                        .doesNotContain(n);
            }
        }
        for (var sub : cmd.subcommands()) {
            assertNoGlobalCollision(sub, qualified + " " + sub.name(), globals);
        }
    }
}
