// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavacLintTest {

    @Test
    void prepends_default_lint_when_enabled() {
        List<String> args = JavacLint.effectiveArgs(true, List.of("-Werror"));
        assertThat(args).containsExactly("-Xlint:deprecation,unchecked", "-Werror");
    }

    @Test
    void omits_lint_when_disabled() {
        assertThat(JavacLint.effectiveArgs(false, List.of("-Werror")))
                .containsExactly("-Werror");
    }

    @Test
    void enabled_with_no_user_args_is_just_the_default() {
        assertThat(JavacLint.effectiveArgs(true, List.of()))
                .containsExactly("-Xlint:deprecation,unchecked");
    }
}
