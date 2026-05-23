// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code jk jdk} — JDK management subcommands. Maps to PRD §12.
 */
@Command(
        name = "jdk",
        description = "Manage JDK versions and installations",
        subcommands = {
                JdkListCommand.class,
                JdkInstallCommand.class,
                JdkPinCommand.class,
                JdkDirCommand.class,
                JdkHomeCommand.class,
                JdkUninstallCommand.class,
                JdkReconcileCommand.class,
                JdkUpdateShellCommand.class,
        })
public final class JdkCommand implements Runnable {

    @Override
    public void run() {
        // No subcommand — print help.
        new CommandLine(this).usage(System.out);
    }
}
