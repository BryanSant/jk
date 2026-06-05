// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code jk library} parent verb. The short-name library catalog is a
 * layered name → {@code group:artifact} index used to resolve manifest
 * shorthands like {@code picocli = "4.7.7"}. Subcommands here manage the
 * downloaded layer and surface lookups.
 *
 * <p>Layered lookup order (highest → lowest): the current project's
 * {@code [libraries]} table, the user's {@code ~/.jk/libs.local.toml}, the
 * downloaded {@code ~/.jk/libs.global.toml} (refreshed by
 * {@link LibraryUpdateCommand}), then the bundled floor that ships with
 * the binary.
 */
@Command(name = "library", aliases = {"lib"},
        description = "Manage the short-name-to-coordinate library catalog",
        subcommands = {
                LibraryUpdateCommand.class,
                LibraryListCommand.class,
                LibrarySearchCommand.class,
        })
public final class LibraryCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 64;
    }
}
