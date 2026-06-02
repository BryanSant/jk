// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code jk jdk} — JDK management subcommands. Maps to PRD §12.
 */
@Command(
        name = "jdk",
        aliases = {"jdks"},
        description = "Manage JDK versions and installations",
        subcommands = {
                JdkListCommand.class,
                JdkInstallCommand.class,
                JdkPinCommand.class,
                JdkDefaultCommand.class,
                JdkHomeCommand.class,
                JdkUninstallCommand.class,
                JdkReconcileCommand.class,
                JdkUpdateShellCommand.class,
        })
public final class JdkCommand implements Runnable {

    @Spec CommandSpec spec;

    @Override
    public void run() {
        // No subcommand — print help via the active CommandLine so the
        // alias-stripping renderer registered by Jk.newCommandLine() applies.
        spec.commandLine().usage(System.out);
    }
}
