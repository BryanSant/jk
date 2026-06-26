// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model.command;

import java.util.List;

/**
 * A command's parseable surface, declared as data (no annotations, no reflection): a name, a
 * one-line description, and the options + positional parameters it accepts. This is the contract
 * jk's own arg parser and help renderer read — and that a future GUI or IDE bridge can render
 * without any CLI framework. See {@link CliCommand} for the executable, CLI-presentable extension.
 */
public interface Command {

    /** The verb (e.g. {@code "build"}, {@code "install"}). */
    String name();

    /** One-line description shown in help and command lists. */
    String description();

    /** Options this command accepts (command-specific; global options are added by the host). */
    default List<Opt> options() {
        return List.of();
    }

    /** Positional parameters, in declaration order. */
    default List<Param> parameters() {
        return List.of();
    }
}
