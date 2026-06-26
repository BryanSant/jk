// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import java.nio.file.Path;

/**
 * Zsh flavour. Uses {@code precmd} + {@code chpwd} hooks via {@code add-zsh-hook} so directory
 * changes update env immediately and a background-spawned tool can refresh on the next prompt.
 */
public final class ZshShell implements Shell {

    @Override
    public String name() {
        return "zsh";
    }

    @Override
    public String setEnv(String key, String value) {
        return "export " + key + "=" + PosixQuote.quote(value) + "\n";
    }

    @Override
    public String unsetEnv(String key) {
        return "unset " + key + "\n";
    }

    @Override
    public String activateScript(String jkExe) {
        return ShellResources.load("zsh.sh").replace("__JK_EXE__", PosixQuote.quote(jkExe));
    }

    @Override
    public String deactivateScript() {
        return ShellResources.load("zsh_deactivate.sh");
    }

    @Override
    public Path rcFile(Path home) {
        return home.resolve(".zshrc");
    }

    @Override
    public String rcFileDisplay() {
        return "~/.zshrc";
    }

    @Override
    public String activationLine(String jkExe) {
        return "eval \"$(" + jkExe + " activate zsh)\"";
    }
}
