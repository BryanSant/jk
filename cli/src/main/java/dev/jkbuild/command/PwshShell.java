// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import java.nio.file.Path;

/**
 * PowerShell flavour. Single-quoted strings with backtick escaping for embedded quotes, newlines,
 * and tabs.
 */
public final class PwshShell implements Shell {

    @Override
    public String name() {
        return "pwsh";
    }

    @Override
    public String setEnv(String key, String value) {
        return "$Env:" + key + " = '" + pwshEscape(value) + "'\n";
    }

    @Override
    public String unsetEnv(String key) {
        return "Remove-Item -ErrorAction SilentlyContinue -Path Env:/" + key + "\n";
    }

    @Override
    public String activateScript(String jkExe) {
        // pwsh templates use straight `'...'` for the exe path — escape any
        // embedded single quotes per PowerShell's `''` doubling rule.
        return ShellResources.load("pwsh.ps1").replace("__JK_EXE__", jkExe.replace("'", "''"));
    }

    @Override
    public String deactivateScript() {
        return ShellResources.load("pwsh_deactivate.ps1");
    }

    @Override
    public Path rcFile(Path home) {
        // Cross-platform $PROFILE on Windows lives under Documents; on macOS
        // and Linux PowerShell uses ~/.config/powershell/. Pick the most
        // common Windows location since pwsh on Unix is rare for jk users.
        return home.resolve("Documents").resolve("PowerShell").resolve("Microsoft.PowerShell_profile.ps1");
    }

    @Override
    public String rcFileDisplay() {
        return "$PROFILE";
    }

    @Override
    public String activationLine(String jkExe) {
        return "(& '" + pwshEscape(jkExe) + "' activate pwsh) | Out-String | Invoke-Expression";
    }

    /**
     * PowerShell single-quoted string escape: doubles embedded single quotes and replaces common
     * control characters with backtick escapes that work inside single-quoted strings.
     */
    static String pwshEscape(String value) {
        var sb = new StringBuilder(value.length() + 2);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\'' -> sb.append("''");
                case '\n' -> sb.append("`n");
                case '\r' -> sb.append("`r");
                case '\t' -> sb.append("`t");
                case '`' -> sb.append("``");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
