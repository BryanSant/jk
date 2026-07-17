// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.command.ide.IdeTarget;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.EnumSet;
import java.util.List;

/**
 * {@code jk vscode} — generate VS Code project files ({@code .vscode/} + Eclipse metadata for the
 * redhat.java language server).
 *
 * <p>An alias of {@code jk ide --vscode}. All generation logic lives in {@link IdeCommand} +
 * {@link cc.jumpkick.command.ide.VscodeIdeGenerator}.
 */
public final class VscodeCommand implements CliCommand {

    private final IdeCommand delegate = new IdeCommand(EnumSet.of(IdeTarget.VSCODE));

    @Override
    public String name() {
        return "vscode";
    }

    @Override
    public String description() {
        return "Generate VS Code project files (alias of `jk ide --vscode`)";
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
