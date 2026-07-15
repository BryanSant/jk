// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.model.command;

/**
 * A {@link CliCommand} that is purely a container for subcommands (e.g. {@code jk jdk}, {@code jk
 * repo}, {@code jk export}). The dispatcher handles a group directly — it prints the group's
 * subcommand list for {@code jk <group>} / {@code jk <group> --help} and never descends into the
 * group's own {@link #run}. That override is therefore required by the interface but unreachable;
 * this base supplies it once (returning the conventional usage exit code) so each group needn't
 * repeat a dead body. Subclasses provide {@link #name()} and {@link #subcommands()}.
 */
public abstract class GroupCommand implements CliCommand {

    /**
     * Unreachable in normal dispatch (the dispatcher renders the subcommand list for a group before
     * it would ever call this). Present only to satisfy {@link CliCommand#run}; returns the usage
     * exit code as a defensive default.
     */
    @Override
    public final int run(Invocation in) {
        return Exit.USAGE;
    }
}
