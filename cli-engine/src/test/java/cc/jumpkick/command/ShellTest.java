// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ShellTest {

    @Test
    void resolves_by_name_case_insensitive() {
        assertThat(Shell.byName("bash")).get().isInstanceOf(BashShell.class);
        assertThat(Shell.byName("ZSH")).get().isInstanceOf(ZshShell.class);
        assertThat(Shell.byName("Fish")).get().isInstanceOf(FishShell.class);
        assertThat(Shell.byName("pwsh")).get().isInstanceOf(PwshShell.class);
        assertThat(Shell.byName("powershell")).get().isInstanceOf(PwshShell.class);
        assertThat(Shell.byName("sh")).get().isInstanceOf(BashShell.class);
    }

    @Test
    void unknown_shell_is_empty() {
        assertThat(Shell.byName("xonsh")).isEmpty();
        assertThat(Shell.byName(null)).isEmpty();
    }

    @Test
    void bash_set_unset_env() {
        var sh = new BashShell();
        assertThat(sh.setEnv("FOO", "bar")).isEqualTo("export FOO=bar\n");
        assertThat(sh.setEnv("PATH", "/x/y:/z")).isEqualTo("export PATH=/x/y:/z\n"); // path-y chars don't need quoting
        assertThat(sh.setEnv("MSG", "hello world")).isEqualTo("export MSG='hello world'\n");
        assertThat(sh.unsetEnv("FOO")).isEqualTo("unset FOO\n");
    }

    @Test
    void bash_escapes_embedded_single_quotes() {
        assertThat(new BashShell().setEnv("X", "it's")).isEqualTo("export X='it'\\''s'\n");
    }

    @Test
    void zsh_set_unset_env() {
        var sh = new ZshShell();
        assertThat(sh.setEnv("JAVA_HOME", "/opt/jdk")).isEqualTo("export JAVA_HOME=/opt/jdk\n");
        assertThat(sh.unsetEnv("JAVA_HOME")).isEqualTo("unset JAVA_HOME\n");
    }

    @Test
    void fish_set_unset_env() {
        var sh = new FishShell();
        assertThat(sh.setEnv("JAVA_HOME", "/opt/jdk")).isEqualTo("set -gx JAVA_HOME /opt/jdk\n");
        assertThat(sh.unsetEnv("FOO")).isEqualTo("set -e FOO\n");
    }

    @Test
    void pwsh_set_unset_env() {
        var sh = new PwshShell();
        assertThat(sh.setEnv("JAVA_HOME", "/opt/jdk")).isEqualTo("$Env:JAVA_HOME = '/opt/jdk'\n");
        assertThat(sh.unsetEnv("FOO")).isEqualTo("Remove-Item -ErrorAction SilentlyContinue -Path Env:/FOO\n");
    }

    @Test
    void pwsh_escapes_single_quotes_via_doubling() {
        assertThat(new PwshShell().setEnv("X", "it's")).isEqualTo("$Env:X = 'it''s'\n");
    }

    @Test
    void activation_script_includes_exe_path() {
        var out = new ZshShell().activateScript("/opt/jk/bin/jk");
        assertThat(out).contains("__JK_EXE=/opt/jk/bin/jk");
        // precmd + chpwd hooks are essential to the contract.
        assertThat(out).contains("add-zsh-hook precmd");
        assertThat(out).contains("add-zsh-hook chpwd");
    }

    @Test
    void bash_activation_uses_prompt_command() {
        var out = new BashShell().activateScript("/opt/jk/bin/jk");
        assertThat(out).contains("PROMPT_COMMAND");
        assertThat(out).contains("hook-env -s bash");
    }

    @Test
    void fish_activation_uses_pwd_hook() {
        var out = new FishShell().activateScript("/opt/jk/bin/jk");
        assertThat(out).contains("--on-variable PWD");
        assertThat(out).contains("--on-event fish_prompt");
        assertThat(out).contains("hook-env -s fish");
    }

    @Test
    void pwsh_activation_uses_chpwd_and_prompt() {
        var out = new PwshShell().activateScript("/opt/jk/bin/jk");
        assertThat(out).contains("LocationChangedAction");
        assertThat(out).contains("global:prompt");
        assertThat(out).contains("hook-env -s pwsh");
    }

    @Test
    void activate_scripts_no_longer_define_a_jkx_function() {
        // `jkx` is a real binary in $JK_BIN_DIR (hardlink to jk, argv[0] dispatch
        // in Jk.main) — a shell function here would shadow it.
        assertThat(new BashShell().activateScript("/opt/jk/bin/jk")).doesNotContain("jkx()");
        assertThat(new ZshShell().activateScript("/opt/jk/bin/jk")).doesNotContain("jkx()");
        assertThat(new FishShell().activateScript("/opt/jk/bin/jk")).doesNotContain("function jkx");
        assertThat(new PwshShell().activateScript("/opt/jk/bin/jk")).doesNotContain("function global:jkx");
    }

    @Test
    void zsh_rc_file_is_dot_zshrc_not_zshenv() {
        // .zshrc is the interactive-shell config; .zshenv runs even for
        // non-interactive subshells where our precmd/chpwd hooks don't fit.
        var home = Path.of("/home/u");
        assertThat(new ZshShell().rcFile(home)).isEqualTo(home.resolve(".zshrc"));
        assertThat(new ZshShell().rcFileDisplay()).isEqualTo("~/.zshrc");
    }

    @Test
    void bash_rc_file_is_dot_bashrc() {
        var home = Path.of("/home/u");
        assertThat(new BashShell().rcFile(home)).isEqualTo(home.resolve(".bashrc"));
        assertThat(new BashShell().rcFileDisplay()).isEqualTo("~/.bashrc");
    }

    @Test
    void fish_rc_file_is_config_fish() {
        var home = Path.of("/home/u");
        assertThat(new FishShell().rcFile(home))
                .isEqualTo(home.resolve(".config").resolve("fish").resolve("config.fish"));
    }

    @Test
    void activation_lines_use_the_right_idiom_per_shell() {
        assertThat(new BashShell().activationLine("/opt/jk/bin/jk"))
                .isEqualTo("eval \"$(/opt/jk/bin/jk activate bash)\"");
        assertThat(new ZshShell().activationLine("/opt/jk/bin/jk"))
                .isEqualTo("eval \"$(/opt/jk/bin/jk activate zsh)\"");
        assertThat(new FishShell().activationLine("/opt/jk/bin/jk")).isEqualTo("/opt/jk/bin/jk activate fish | source");
        assertThat(new PwshShell().activationLine("/opt/jk/bin/jk"))
                .contains("Invoke-Expression")
                .contains("activate pwsh");
    }

    @Test
    void detect_resolves_shell_from_path_basename() {
        assertThat(Shell.detect("/bin/zsh")).get().isInstanceOf(ZshShell.class);
        assertThat(Shell.detect("/usr/local/bin/fish")).get().isInstanceOf(FishShell.class);
        assertThat(Shell.detect("bash")).get().isInstanceOf(BashShell.class);
        assertThat(Shell.detect("/usr/bin/dash")).isEmpty();
        assertThat(Shell.detect(null)).isEmpty();
        assertThat(Shell.detect("")).isEmpty();
    }
}
