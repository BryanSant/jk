// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The model-driven help path: a {@link CliCommand} → {@link CommandModel}
 * (via {@link CommandModels}) → rendered screen (via
 * {@link HelpRenderer#renderHelp}). Ansi off for deterministic assertions.
 */
class CommandHelpRenderTest {

    private static final CliCommand DEMO = new CliCommand() {
        @Override
        public String name() {
            return "demo";
        }

        @Override
        public String description() {
            return "Do a demo thing";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.flag("Suppress output", "-q", "--quiet"),
                    Opt.value("<name>", "Profile to apply", "--profile"),
                    Opt.flag("internal", "--secret").hide());
        }

        @Override
        public List<Param> parameters() {
            return List.of(Param.of("file", Arity.ZERO_OR_ONE, "File to read"));
        }

        @Override
        public int run(Invocation in) {
            return 0;
        }
    };

    @Test
    void rendersAllSections() {
        OptionModel verbose = new OptionModel("-v, --verbose", "", new String[] {"Verbose output"});
        CommandModel model = CommandModels.from(DEMO, "jk demo", List.of(verbose));
        String help = HelpRenderer.renderHelp(model, /* ansi */ false);

        assertThat(help).contains("Do a demo thing");
        assertThat(help).contains("Usage: jk demo [file] [OPTIONS]");
        assertThat(help).contains("Parameters:");
        assertThat(help).contains("[file]").contains("File to read");
        assertThat(help).contains("Options:");
        assertThat(help).contains("-q, --quiet").contains("Suppress output");
        assertThat(help).contains("--profile").contains("<name>").contains("Profile to apply");
        assertThat(help).contains("Global options:");
        assertThat(help).contains("-v, --verbose").contains("Verbose output");
    }

    @Test
    void hiddenOptionsAreOmitted() {
        CommandModel model = CommandModels.from(DEMO, "jk demo", List.of());
        String help = HelpRenderer.renderHelp(model, false);
        assertThat(help).doesNotContain("--secret");
        assertThat(help).doesNotContain("Global options:"); // none supplied
    }

    @Test
    void requiredParamRendersAngleBrackets() {
        CliCommand req = new CliCommand() {
            @Override
            public String name() {
                return "add";
            }

            @Override
            public String description() {
                return "Add a dep";
            }

            @Override
            public List<Param> parameters() {
                return List.of(Param.of("coord", Arity.ONE, "group:artifact:version"));
            }

            @Override
            public int run(Invocation in) {
                return 0;
            }
        };
        String help = HelpRenderer.renderHelp(CommandModels.from(req, "jk add", List.of()), false);
        assertThat(help).contains("Usage: jk add <coord> [OPTIONS]");
    }
}
