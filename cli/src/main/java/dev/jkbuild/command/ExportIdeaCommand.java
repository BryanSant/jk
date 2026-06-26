// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import java.util.List;

/**
 * {@code jk export idea} — generate IntelliJ IDEA project files. Shares
 * {@code jk idea}'s behavior exactly; provided under {@code export} so the
 * three target systems (gradle / maven / idea) live together.
 */
public final class ExportIdeaCommand implements CliCommand {

    private final IdeaCommand delegate = new IdeaCommand();

    @Override
    public String name() {
        return "idea";
    }

    @Override
    public String description() {
        return "Generate IntelliJ IDEA project files (alias of `jk idea`)";
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
