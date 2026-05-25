// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.activate;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code jk deactivate} — emit the shell script that undoes {@code jk activate}.
 * The activation-side {@code jk()} proxy intercepts this call and runs the
 * output through {@code eval} / {@code source} so the current shell loses
 * its hooks immediately.
 */
@Command(name = "deactivate",
        description = "Tear down the shell integration installed by `jk activate`")
public final class DeactivateCommand implements Callable<Integer> {

    @Option(names = {"-s", "--shell"},
            description = "Shell to emit for. Defaults to $__JK_SHELL.")
    String shellName;

    @Override
    public Integer call() {
        var name = shellName != null ? shellName : System.getenv("__JK_SHELL");
        if (name == null || name.isBlank()) {
            System.err.println("jk deactivate: no active shell (re-run from a `jk activate`'d shell)");
            return 64;
        }
        var shell = Shell.byName(name);
        if (shell.isEmpty()) {
            System.err.println("jk deactivate: unsupported shell `" + name + "`");
            return 64;
        }
        System.out.print(shell.get().deactivateScript());
        return 0;
    }
}
