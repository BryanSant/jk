// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.List;

/**
 * {@code jk deactivate} — emit the shell script that undoes {@code jk activate}. The
 * activation-side {@code jk()} proxy intercepts this call and runs the output through {@code eval}
 * / {@code source} so the current shell loses its hooks immediately.
 */
public final class DeactivateCommand implements CliCommand {

    @Override
    public String name() {
        return "deactivate";
    }

    @Override
    public String description() {
        return "Tear down jk's shell integration";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.value("<shell>", "Shell to emit for. Defaults to $__JK_SHELL.", "-s", "--shell"));
    }

    @Override
    public int run(Invocation in) {
        String name = in.value("shell").orElseGet(() -> System.getenv("__JK_SHELL"));
        if (name == null || name.isBlank()) {
            CliOutput.err("jk deactivate: no active shell (re-run from a `jk activate`'d shell)");
            return Exit.USAGE;
        }
        var shell = Shell.byName(name);
        if (shell.isEmpty()) {
            CliOutput.err("jk deactivate: unsupported shell `" + name + "`");
            return Exit.USAGE;
        }
        CliOutput.outRaw(shell.get().deactivateScript());
        return 0;
    }
}
