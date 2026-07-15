// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.GroupCommand;
import java.util.List;

/** {@code jk jdk} parent — Manage JDK versions and installations */
public final class JdkCommand extends GroupCommand {

    @Override
    public String name() {
        return "jdk";
    }

    @Override
    public String description() {
        return "Manage JDK versions and installations";
    }

    @Override
    public List<String> aliases() {
        return List.of("jdks");
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new JdkListCommand(),
                new JdkInstallCommand(),
                new JdkEnsureCommand(),
                new JdkUpdateCommand(),
                new JdkPinCommand(),
                new JdkDefaultCommand(),
                new JdkGraalCommand(),
                new JdkHomeCommand(),
                new JdkUninstallCommand(),
                new JdkUpdateShellCommand());
    }
}
