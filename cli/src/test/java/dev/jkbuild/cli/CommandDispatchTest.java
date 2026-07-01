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
}
