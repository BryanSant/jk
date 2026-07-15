// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JavacLintTest {

    @Test
    void prepends_default_lint_when_enabled() {
        List<String> args = JavacLint.effectiveArgs(true, List.of("-Werror"));
        assertThat(args).containsExactly("-Xlint:deprecation,unchecked", "-Werror");
    }

    @Test
    void omits_lint_when_disabled() {
        assertThat(JavacLint.effectiveArgs(false, List.of("-Werror"))).containsExactly("-Werror");
    }

    @Test
    void enabled_with_no_user_args_is_just_the_default() {
        assertThat(JavacLint.effectiveArgs(true, List.of())).containsExactly("-Xlint:deprecation,unchecked");
    }

    @Test
    void parameters_default_adds_the_flag_before_user_args() {
        assertThat(JavacLint.effectiveArgs(true, List.of("-parameters"), List.of("-Werror")))
                .containsExactly("-Xlint:deprecation,unchecked", "-parameters", "-Werror");
    }

    @Test
    void parameters_default_is_not_duplicated_when_the_user_passes_it() {
        assertThat(JavacLint.effectiveArgs(false, List.of("-parameters"), List.of("-parameters"))).containsExactly("-parameters");
    }
}
