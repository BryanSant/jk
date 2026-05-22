// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code jk jdk} — JDK management subcommands. Parent for install / list /
 * use / uninstall. Maps to PRD §12.
 */
@Command(
        name = "jdk",
        description = "Manage installed JDKs.",
        subcommands = {
                JdkInstallCommand.class,
                JdkListCommand.class,
                JdkUseCommand.class,
                JdkUninstallCommand.class,
        })
public final class JdkCommand implements Runnable {

    @Override
    public void run() {
        // No subcommand — print help.
        new CommandLine(this).usage(System.out);
    }
}
