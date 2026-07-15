// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import java.util.List;

/**
 * {@code jk export idea} — generate IntelliJ IDEA project files. Shares {@code jk ide --idea}'s
 * behavior exactly; provided under {@code export} so the three target systems (gradle / maven /
 * idea) live together.
 */
public final class ExportIdeaCommand implements CliCommand {

    private final IdeCommand delegate =
            new IdeCommand(java.util.EnumSet.of(build.jumpkick.command.ide.IdeTarget.IDEA));

    @Override
    public String name() {
        return "idea";
    }

    @Override
    public String description() {
        return "Generate IntelliJ IDEA project files (alias of `jk ide --idea`)";
    }

    @Override
    public List<Opt> options() {
        return delegate.options();
    }

    @Override
    public int run(Invocation in) throws Exception {
        return delegate.run(in);
    }
}
