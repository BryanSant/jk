// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.args.ArgParser;
import dev.jkbuild.model.command.Command;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The --variant flag is documented "repeatable" and must actually be: before .repeat() was added,
 * ArgParser's last-wins value map silently dropped every occurrence but the last.
 */
class VariantSelectionTest {

    private static Invocation parse(String... args) throws Exception {
        Command cmd = new Command() {
            @Override
            public String name() {
                return "demo";
            }

            @Override
            public String description() {
                return "demo";
            }

            @Override
            public List<Opt> options() {
                return VariantSelection.options();
            }

            @Override
            public List<Param> parameters() {
                return List.of();
            }
        };
        return ArgParser.parse(cmd, List.of(args));
    }

    @Test
    void repeated_variant_flags_all_survive() throws Exception {
        Invocation in = parse("--variant", "build-type=release", "--variant", "tier=free");
        assertThat(VariantSelection.selector(in)).isEqualTo("release|tier=free");
    }

    @Test
    void comma_form_and_repeats_compose() throws Exception {
        Invocation in = parse("--variant", "tier=free,region=eu", "--variant", "abi=arm64");
        assertThat(VariantSelection.selector(in)).isEqualTo("tier=free|region=eu|abi=arm64");
    }

    @Test
    void release_shorthand_overrides_an_explicit_build_type() throws Exception {
        Invocation in = parse("--variant", "build-type=debug", "--release");
        assertThat(VariantSelection.selector(in)).isEqualTo("release");
    }
}
