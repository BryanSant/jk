// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code jk registry} parent verb. The short-name alias registry is a
 * layered name → {@code group:artifact} index used to resolve manifest
 * shorthands like {@code picocli = "4.7.7"}. Subcommands here manage the
 * downloaded layer and surface lookups.
 *
 * <p>Layered lookup order (highest → lowest): the current project's
 * {@code [aliases]} table, the user's {@code ~/.jk/aliases.toml}, the
 * downloaded {@code ~/.jk/registry/aliases.toml} (refreshed by
 * {@link RegistryUpdateCommand}), then the bundled floor that ships with
 * the binary.
 */
@Command(name = "registry",
        description = "Manage the short-name → coordinate alias registry",
        subcommands = {
                RegistryUpdateCommand.class,
                RegistryListCommand.class,
        })
public final class RegistryCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 64;
    }
}
