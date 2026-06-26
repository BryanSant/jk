// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

/**
 * A subcommand as shown in a command list.
 *
 * @param name the subcommand's canonical name
 * @param description the subcommand's description lines (may be empty)
 * @param hidden true when the subcommand is hidden from help
 */
public record SubcommandModel(String name, String[] description, boolean hidden) {}
