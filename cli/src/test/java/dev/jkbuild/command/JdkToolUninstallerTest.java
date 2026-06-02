// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkVendor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercise the source → command-line mapping. We can't safely run the real
 * subprocesses in a unit test (they'd mutate the host's actual SDKMAN /
 * mise / etc. state), so this test pokes at the static command shapes the
 * helper builds — enough to lock the contract for each tool and catch
 * accidental flag drift.
 */
class JdkToolUninstallerTest {

    @Test
    void unknown_source_returns_fall_through() {
        // intellij + java-home aren't tool-managed → no recipe → caller
        // gets FALL_THROUGH and runs the direct purge.
        var hit = hit(Path.of("/home/u/.jdks/temurin-26.0.1"), "intellij");
        // Path doesn't exist on the test machine; tryUninstall short-circuits
        // before invoking anything because commandFor returns null.
        assertThat(JdkToolUninstaller.tryUninstall(hit, "temurin-26.0.1"))
                .isEqualTo(JdkToolUninstaller.Outcome.FALL_THROUGH);
    }

    @Test
    void sdkman_command_sources_init_then_invokes_uninstall() {
        // The string "sdk uninstall java <id>" has to appear in the shell
        // command so SDKMAN runs in the same process — guards against an
        // accidental drop of the sdkman-init.sh sourcing.
        var actual = commandFor("sdkman", "25.0.3-tem");
        assertThat(actual).hasSize(3);
        assertThat(actual.get(0)).isEqualTo("bash");
        assertThat(actual.get(1)).isEqualTo("-c");
        assertThat(actual.get(2)).contains("sdkman-init.sh")
                .contains("sdk uninstall java")
                .contains("'25.0.3-tem'");
    }

    @Test
    void mise_command_uses_yes_flag_for_non_interactive() {
        assertThat(commandFor("mise", "temurin-21.0.5"))
                .containsExactly("mise", "uninstall", "--yes", "java@temurin-21.0.5");
    }

    @Test
    void jbang_command() {
        assertThat(commandFor("jbang", "temurin-17.0.19"))
                .containsExactly("jbang", "jdk", "uninstall", "temurin-17.0.19");
    }

    @Test
    void jenv_command_removes_alias_only_caller_falls_back_for_files() {
        // jenv doesn't own JDK files; this command just unregisters the
        // alias. The dir-exists check downstream will trigger FALL_THROUGH
        // so the actual install gets purged too.
        assertThat(commandFor("jenv", "temurin-21.0.5"))
                .containsExactly("jenv", "remove", "temurin-21.0.5");
    }

    @Test
    void asdf_command() {
        assertThat(commandFor("asdf", "temurin-21.0.5"))
                .containsExactly("asdf", "uninstall", "java", "temurin-21.0.5");
    }

    @Test
    void homebrew_extracts_formula_from_cellar_path() {
        // Home → ...Cellar/openjdk@21/21.0.5/libexec/openjdk.jdk/Contents/Home
        var home = Path.of("/opt/homebrew/Cellar/openjdk@21/21.0.5"
                + "/libexec/openjdk.jdk/Contents/Home");
        var hit = hit(home, "homebrew");
        var cmd = JdkToolUninstaller.class
                .getDeclaredMethods();
        // Use reflection through the package-private commandFor for verification.
        assertThat(commandFor(hit, "openjdk.jdk"))
                .containsExactly("brew", "uninstall", "openjdk@21");
    }

    @Test
    void homebrew_returns_null_when_path_is_not_a_cellar_install() {
        // Some non-brew path under /opt — we shouldn't claim to handle it.
        var home = Path.of("/opt/jdk/temurin-21/Contents/Home");
        assertThat(commandFor(hit(home, "homebrew"), "temurin-21")).isNull();
    }

    // --- helpers ----------------------------------------------------------

    private static JdkHit hit(Path home, String source) {
        return new JdkHit(home, "21.0.5", JdkVendor.TEMURIN, source);
    }

    /** Reflective shim so tests can poke at the private command builder. */
    private static java.util.List<String> commandFor(String source, String identifier) {
        return commandFor(hit(Path.of("/tmp/" + identifier), source), identifier);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> commandFor(JdkHit hit, String identifier) {
        try {
            var m = JdkToolUninstaller.class.getDeclaredMethod(
                    "commandFor", JdkHit.class, String.class);
            m.setAccessible(true);
            return (java.util.List<String>) m.invoke(null, hit, identifier);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
