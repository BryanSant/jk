// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntellijJdkTableTest {

    @Test
    void recognizes_a_jdk_registered_in_a_jetbrains_table(@TempDir Path tempDir) throws IOException {
        Path userHome = tempDir.resolve("home");
        Path jdkHome = userHome.resolve(".jdks").resolve("graalvm-ce-24.0.2");
        Files.createDirectories(jdkHome.resolve("bin")); // make it canonicalizable

        Path config = tempDir.resolve("config");
        Path table = config.resolve("JetBrains")
                .resolve("IntelliJIdea2024.3")
                .resolve("options")
                .resolve("jdk.table.xml");
        Files.createDirectories(table.getParent());
        Files.writeString(table, """
                <application>
                  <component name="ProjectJdkTable">
                    <jdk version="2">
                      <name value="graalvm-ce-24.0.2" />
                      <type value="JavaSDK" />
                      <homePath value="$USER_HOME$/.jdks/graalvm-ce-24.0.2" />
                      <roots />
                    </jdk>
                  </component>
                </application>
                """);

        IntellijJdkTable t = new IntellijJdkTable(
                List.of(config.resolve("JetBrains"), config.resolve("Google")), userHome.toString());

        assertThat(t.isManaged(jdkHome.toRealPath())).isTrue();
        assertThat(t.isManaged(userHome.resolve(".jdks").resolve("temurin-21").toAbsolutePath()))
                .isFalse();
    }

    @Test
    void empty_when_no_config_dirs_exist(@TempDir Path tempDir) {
        IntellijJdkTable t = new IntellijJdkTable(List.of(tempDir.resolve("absent")), tempDir.toString());
        assertThat(t.isManaged(tempDir.resolve("whatever"))).isFalse();
    }

    @Test
    void default_vendor_roots_cover_jetbrains_and_google_per_platform() {
        // macOS → ~/Library/Application Support/{JetBrains,Google}
        assertThat(IntellijJdkTable.defaultVendorRoots(k -> null, "Mac OS X", "/Users/x"))
                .containsExactly(
                        Path.of("/Users/x/Library/Application Support/JetBrains"),
                        Path.of("/Users/x/Library/Application Support/Google"));

        // Linux honours XDG_CONFIG_HOME
        assertThat(IntellijJdkTable.defaultVendorRoots(
                        k -> "XDG_CONFIG_HOME".equals(k) ? "/cfg" : null, "Linux", "/home/x"))
                .containsExactly(Path.of("/cfg/JetBrains"), Path.of("/cfg/Google"));

        // Linux without XDG falls back to ~/.config
        assertThat(IntellijJdkTable.defaultVendorRoots(k -> null, "Linux", "/home/x"))
                .containsExactly(Path.of("/home/x/.config/JetBrains"), Path.of("/home/x/.config/Google"));
    }
}
