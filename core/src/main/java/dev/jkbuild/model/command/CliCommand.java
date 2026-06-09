// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model.command;

import java.util.List;

/**
 * An executable, CLI-presentable {@link Command}: it can have aliases and
 * subcommands, and it runs against a parsed {@link Invocation}, returning the
 * process exit code. Replaces the picocli {@code @Command} + {@code Callable<Integer>}
 * shape — leaf verbs implement {@link #run}, parent verbs return subcommands
 * (and typically {@code run} prints help).
 */
public interface CliCommand extends Command {

    /** Hidden alternate verbs (e.g. {@code jdk} ⇄ {@code jdks}); not shown in help. */
    default List<String> aliases() {
        return List.of();
    }

    /** Subcommands, in registration order; empty for a leaf command. */
    default List<CliCommand> subcommands() {
        return List.of();
    }

    /** True when this command has no subcommands. */
    default boolean isLeaf() {
        return subcommands().isEmpty();
    }

    /** True to omit this command from the top-level help listing (still dispatchable). */
    default boolean hidden() {
        return false;
    }

    /**
     * True when unrecognized options should be forwarded as positional arguments
     * rather than treated as errors — needed for passthrough commands like
     * {@code jk mvn} / {@code jk gradle} that relay unknown flags to a child process.
     */
    default boolean passthrough() {
        return false;
    }

    /**
     * Execute with the parsed arguments; return the process exit code
     * (0 = success). Parent commands typically print help and return a usage
     * exit code when invoked without a subcommand.
     */
    int run(Invocation in) throws Exception;
}
