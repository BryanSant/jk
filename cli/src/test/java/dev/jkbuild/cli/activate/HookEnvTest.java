// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.activate;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HookEnvTest {

    @Test
    void no_project_with_no_prior_diff_emits_nothing() {
        var sh = new BashShell();
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, JkEnv.Target.empty(), JkDiff.empty(), k -> null, out);
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void entering_a_project_exports_target_vars_and_stores_diff() {
        var sh = new BashShell();
        var target = new JkEnv.Target(Optional.of(Path.of("/proj")), Map.of(
                "JAVA_HOME", "/opt/jdk-25",
                "PATH", "/opt/jdk-25/bin:/usr/bin"));
        var out = new StringBuilder();
        // Before activation: only PATH is set in the env, JAVA_HOME is unset.
        JkDiff.EnvSnapshot snap = k -> "PATH".equals(k) ? "/usr/bin" : null;
        HookEnvCommand.emit(sh, target, JkDiff.empty(), snap, out);

        var s = out.toString();
        assertThat(s).contains("export JAVA_HOME=/opt/jdk-25");
        assertThat(s).contains("export PATH=/opt/jdk-25/bin:/usr/bin");
        assertThat(s).contains("export __JK_DIFF=");
    }

    @Test
    void leaving_a_project_restores_originals() {
        var sh = new BashShell();
        // Prior diff: jk previously set JAVA_HOME (was unset before) and PATH (was /usr/bin).
        var prior = new JkDiff(orderedMap(
                "JAVA_HOME", JkDiff.UNSET_SENTINEL,
                "PATH", "/usr/bin"));
        var out = new StringBuilder();
        // Target.empty() = no project here.
        HookEnvCommand.emit(sh, JkEnv.Target.empty(), prior, k -> null, out);

        var s = out.toString();
        // JAVA_HOME was unset before jk activated → unset it now.
        assertThat(s).contains("unset JAVA_HOME");
        // PATH had a value before → restore it.
        assertThat(s).contains("export PATH=/usr/bin");
        // __JK_DIFF should be unset since we no longer track anything.
        assertThat(s).contains("unset __JK_DIFF");
    }

    @Test
    void switching_between_projects_keeps_pre_activation_values_in_diff() {
        var sh = new BashShell();
        // Already in project A: jk previously set JAVA_HOME (was /sys/jdk).
        var prior = new JkDiff(Map.of(
                "JAVA_HOME", "/sys/jdk",
                "PATH", "/usr/bin"));
        // Now entering project B: new target.
        var target = new JkEnv.Target(Optional.of(Path.of("/b")), Map.of(
                "JAVA_HOME", "/opt/jdk-25",
                "PATH", "/opt/jdk-25/bin:/usr/bin"));
        var out = new StringBuilder();
        // Live env has project A's values right now — should NOT overwrite our diff.
        JkDiff.EnvSnapshot snap = k -> switch (k) {
            case "JAVA_HOME" -> "/proj-a/jdk";
            case "PATH" -> "/proj-a/jdk/bin:/usr/bin";
            default -> null;
        };
        HookEnvCommand.emit(sh, target, prior, snap, out);

        var s = out.toString();
        assertThat(s).contains("export JAVA_HOME=/opt/jdk-25");
        // The encoded diff should still carry /sys/jdk so a future cd-out restores correctly.
        // We can't easily decode the base64 in this test without duplicating logic, but
        // we can re-parse it via the JkDiff API.
        var encoded = extractEnvAssignment(s, "__JK_DIFF");
        var next = JkDiff.parse(encoded);
        assertThat(next.previousValue("JAVA_HOME")).isEqualTo("/sys/jdk");
    }

    @Test
    void zsh_emission_uses_export_syntax() {
        var sh = new ZshShell();
        var target = new JkEnv.Target(Optional.of(Path.of("/p")), Map.of("JAVA_HOME", "/opt/jdk"));
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, JkDiff.empty(), k -> null, out);
        assertThat(out.toString()).contains("export JAVA_HOME=/opt/jdk");
    }

    @Test
    void fish_emission_uses_set_gx_syntax() {
        var sh = new FishShell();
        var target = new JkEnv.Target(Optional.of(Path.of("/p")), Map.of("JAVA_HOME", "/opt/jdk"));
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, JkDiff.empty(), k -> null, out);
        assertThat(out.toString()).contains("set -gx JAVA_HOME /opt/jdk");
    }

    @Test
    void pwsh_emission_uses_env_assignment() {
        var sh = new PwshShell();
        var target = new JkEnv.Target(Optional.of(Path.of("/p")), Map.of("JAVA_HOME", "/opt/jdk"));
        var out = new StringBuilder();
        HookEnvCommand.emit(sh, target, JkDiff.empty(), k -> null, out);
        assertThat(out.toString()).contains("$Env:JAVA_HOME = '/opt/jdk'");
    }

    /** Extract the value of {@code export KEY=value} (or fish/pwsh equivalent). */
    private static String extractEnvAssignment(String script, String key) {
        for (var line : script.split("\n")) {
            var bash = "export " + key + "=";
            if (line.startsWith(bash)) {
                var v = line.substring(bash.length());
                if (v.startsWith("'") && v.endsWith("'")) v = v.substring(1, v.length() - 1);
                return v.replace("'\\''", "'");
            }
        }
        return "";
    }

    private static <K, V> Map<K, V> orderedMap(Object... kvs) {
        var m = new LinkedHashMap<K, V>();
        for (int i = 0; i < kvs.length; i += 2) {
            @SuppressWarnings("unchecked") K k = (K) kvs[i];
            @SuppressWarnings("unchecked") V v = (V) kvs[i + 1];
            m.put(k, v);
        }
        return m;
    }
}
