// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code jk alias} parent verb. The short-name alias catalog is a
 * layered name → {@code group:artifact} index used to resolve manifest
 * shorthands like {@code picocli = "4.7.7"}. Subcommands here manage the
 * downloaded layer and surface lookups.
 *
 * <p>Layered lookup order (highest → lowest): the current project's
 * {@code [aliases]} table, the user's {@code ~/.jk/aliases.local.toml}, the
 * downloaded {@code ~/.jk/aliases.global.toml} (refreshed by
 * {@link AliasUpdateCommand}), then the bundled floor that ships with
 * the binary.
 */
@Command(name = "alias",
        description = "Manage the short-name-to-coordinate alias catalog",
        subcommands = {
                AliasUpdateCommand.class,
                AliasListCommand.class,
                AliasSearchCommand.class,
        })
public final class AliasCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 64;
    }
}
