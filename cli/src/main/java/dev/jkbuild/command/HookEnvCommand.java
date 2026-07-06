// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * {@code jk hook-env -s <shell>} (hidden) — emitted by the activation script on every prompt/cd.
 * Outputs the shell commands needed to bring {@code JAVA_HOME} / {@code PATH} into sync with the
 * project at the current working directory.
 *
 * <p>Diffing logic mirrors mise's hook-env: track previously-set keys in {@code __JK_DIFF} so
 * leaving a project (or switching projects) can restore the original values rather than appending
 * forever.
 */
public final class HookEnvCommand implements CliCommand {

    @Override
    public String name() {
        return "hook-env";
    }

    @Override
    public String description() {
        return "Internal: emit env-sync commands for the current directory";
    }

    @Override
    public boolean hidden() {
        return true;
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.value("<shell>", "Shell: bash | zsh | fish | pwsh.", "-s", "--shell")
                .require());
    }

    @Override
    public int run(Invocation in) throws IOException {
        String shellName = in.value("shell").orElseThrow();
        var shell = Shell.byName(shellName);
        if (shell.isEmpty()) {
            CliOutput.err("jk hook-env: unsupported shell `" + shellName + "`");
            return Exit.USAGE;
        }
        var cwd = new GlobalOptions().workingDir();
        var env = JkEnv.defaults();
        var prevDiff = JkDiff.parse(System.getenv("__JK_DIFF"));
        var target = env.resolve(cwd);

        var out = new StringBuilder();
        var snapshot = JkDiff.EnvSnapshot.fromSystem();
        emit(shell.get(), target, prevDiff, snapshot, out);
        CliOutput.outRaw(String.valueOf(out));
        return 0;
    }

    /**
     * Compute the diff between {@code prevDiff}'s tracked keys and {@code target}'s desired state,
     * append shell commands to {@code out}, and write the updated {@code __JK_DIFF}.
     *
     * <p>Package-private so tests can drive it without a real shell.
     */
    static void emit(
            Shell shell, JkEnv.Target target, JkDiff prevDiff, JkDiff.EnvSnapshot snapshot, StringBuilder out) {
        var nextDiff = prevDiff.next(target, snapshot);

        // 1. Keys the prior diff tracked but the new target no longer owns —
        //    restore the original value (or unset).
        var dropped = new LinkedHashSet<>(prevDiff.keys());
        dropped.removeAll(target.vars().keySet());
        for (var key : dropped) {
            if (prevDiff.wasUnset(key)) {
                out.append(shell.unsetEnv(key));
            } else {
                out.append(shell.setEnv(key, prevDiff.previousValue(key)));
            }
        }

        // 2. Keys in the new target — set them. (Even if unchanged from a
        //    prior hook-env call, re-emitting is cheap and keeps the shell
        //    self-consistent across re-sourcing the activate script.)
        for (var entry : target.vars().entrySet()) {
            out.append(shell.setEnv(entry.getKey(), entry.getValue()));
        }

        // 3. Update __JK_DIFF (or unset it when there's nothing to remember).
        var encoded = nextDiff.encode();
        if (encoded.isEmpty()) {
            if (!prevDiff.keys().isEmpty()) {
                out.append(shell.unsetEnv("__JK_DIFF"));
            }
        } else {
            out.append(shell.setEnv("__JK_DIFF", encoded));
        }
    }
}
