// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandDispatchTest {

    @Test
    void commandIndex_findsFirstPositional() {
        assertThat(CommandDispatch.commandIndex(List.of("build"))).isZero();
        assertThat(CommandDispatch.commandIndex(List.of("-q", "build"))).isEqualTo(1);
    }

    @Test
    void commandIndex_skipsValueTakingGlobalAndItsArgument() {
        // -C consumes the next token, so the command is at index 2.
        assertThat(CommandDispatch.commandIndex(List.of("-C", "/tmp", "build"))).isEqualTo(2);
    }

    @Test
    void commandIndex_skipsAbbreviatedValueTakingGlobal() {
        // --dir is a unique prefix of the value-taking global --directory, so it consumes /tmp too.
        assertThat(CommandDispatch.commandIndex(List.of("--dir", "/tmp", "build"))).isEqualTo(2);
    }

    @Test
    void commandIndex_inlineValueGlobalDoesNotConsumeNext() {
        assertThat(CommandDispatch.commandIndex(List.of("--directory=/tmp", "build"))).isEqualTo(1);
    }

    @Test
    void commandIndex_doubleDashSelectsFollowingToken() {
        assertThat(CommandDispatch.commandIndex(List.of("--", "build"))).isEqualTo(1);
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
            cc.jumpkick.model.command.CliCommand cmd, String qualified, java.util.Set<String> globals) {
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
