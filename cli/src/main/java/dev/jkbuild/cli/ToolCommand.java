// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * {@code jk tool} parent verb. Subcommands form the install / list /
 * uninstall / run / dir skeleton shared with {@code jk jdk}; they manage
 * CLI tools the user installs from Maven coordinates.
 *
 * <p>Build tools jk drives implicitly (mvn, gradle, kotlin) are
 * <i>not</i> managed here — those land via {@code jk mvn} / {@code jk
 * gradle} passthrough and are repaired via {@code jk doctor}.
 */
@Command(name = "tool",
        description = "Manage installed CLI tools",
        subcommands = {
                ToolInstallCommand.class,
                ToolListCommand.class,
                ToolUninstallCommand.class,
                ToolRunCommand.class,
                ToolDirCommand.class,
        })
public final class ToolCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new picocli.CommandLine(this).usage(System.out);
        return 64;
    }
}
