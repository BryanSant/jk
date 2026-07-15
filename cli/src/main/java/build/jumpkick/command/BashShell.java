// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import java.nio.file.Path;

/**
 * Bash flavour. Uses {@code PROMPT_COMMAND} as the prompt-time hook since bash lacks a native
 * {@code chpwd} event.
 */
public final class BashShell implements Shell {

    @Override
    public String name() {
        return "bash";
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
        return ShellResources.load("bash.sh").replace("__JK_EXE__", PosixQuote.quote(jkExe));
    }

    @Override
    public String deactivateScript() {
        return ShellResources.load("bash_deactivate.sh");
    }

    @Override
    public Path rcFile(Path home) {
        return home.resolve(".bashrc");
    }

    @Override
    public String rcFileDisplay() {
        return "~/.bashrc";
    }

    @Override
    public String activationLine(String jkExe) {
        return "eval \"$(" + jkExe + " activate bash)\"";
    }
}
