// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntellijSdkRegistrarTest {

    private static final IntellijSdkRegistrar.SdkEntry T25 =
            new IntellijSdkRegistrar.SdkEntry("jk-temurin-25", Path.of("/home/x/.jk/jdks/temurin-25"), "25.0.3");

    @Test
    void creates_table_when_absent(@TempDir Path tmp) throws IOException {
        Path jb = tmp.resolve("JetBrains");
        Files.createDirectories(jb.resolve("IntelliJIdea2025.1").resolve("options"));

        List<Path> touched =
                IntellijSdkRegistrar.of(List.of(jb, tmp.resolve("Google"))).register(List.of(T25));

        assertThat(touched).hasSize(1);
        String xml = Files.readString(jb.resolve("IntelliJIdea2025.1/options/jdk.table.xml"));
        assertThat(xml)
                .contains("ProjectJdkTable")
                .contains("jk-temurin-25")
                .contains("JavaSDK")
                .contains("/home/x/.jk/jdks/temurin-25")
                .contains("jrt:///home/x/.jk/jdks/temurin-25!/");
    }

    @Test
    void emits_one_jrt_root_per_module_for_a_modular_home(@TempDir Path tmp) throws IOException {
        // A JDK home whose `release` advertises its modules — IntelliJ needs a
        // jrt root per module, not one bare root over the image.
        Path home = tmp.resolve("jdk25");
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"25.0.3\"\nMODULES=\"java.base java.desktop jdk.compiler\"\n");
        Path jb = tmp.resolve("JetBrains");
        Files.createDirectories(jb.resolve("IntelliJIdea2025.1/options"));

        IntellijSdkRegistrar.of(List.of(jb, tmp.resolve("Google")))
                .register(List.of(new IntellijSdkRegistrar.SdkEntry("jk-graalvm-25", home, "25.0.3")));

        String xml = Files.readString(jb.resolve("IntelliJIdea2025.1/options/jdk.table.xml"));
        String h = home.toAbsolutePath().normalize().toString();
        assertThat(xml)
                .contains("jrt://" + h + "!/java.base")
                .contains("jrt://" + h + "!/java.desktop")
                .contains("jrt://" + h + "!/jdk.compiler")
                .doesNotContain("jrt://" + h + "!/\""); // no bare image root
    }

    @Test
    void upsert_preserves_other_jdks_and_is_idempotent(@TempDir Path tmp) throws IOException {
        Path jb = tmp.resolve("JetBrains");
        Path opt = jb.resolve("IntelliJIdea2025.1/options");
        Files.createDirectories(opt);
        Path table = opt.resolve("jdk.table.xml");
        Files.writeString(table, """
                <application>
                  <component name="ProjectJdkTable">
                    <jdk version="2">
                      <name value="corretto-21" />
                      <type value="JavaSDK" />
                      <homePath value="/opt/corretto-21" />
                    </jdk>
                  </component>
                </application>
                """);

        IntellijSdkRegistrar r = IntellijSdkRegistrar.of(List.of(jb, tmp.resolve("Google")));
        r.register(List.of(T25));
        r.register(List.of(T25)); // second run must not duplicate

        String xml = Files.readString(table);
        assertThat(xml).contains("corretto-21"); // existing entry preserved
        assertThat(count(xml, "jk-temurin-25")).isEqualTo(1); // no duplicate

        // Re-register with a new home → updates in place.
        r.register(List.of(new IntellijSdkRegistrar.SdkEntry("jk-temurin-25", Path.of("/jdks2/temurin-25"), "25.0.4")));
        xml = Files.readString(table);
        assertThat(xml).contains("/jdks2/temurin-25");
        assertThat(xml).doesNotContain("/home/x/.jk/jdks/temurin-25");
        assertThat(count(xml, "jk-temurin-25")).isEqualTo(1);
        assertThat(xml).contains("corretto-21");
    }

    @Test
    void skips_non_java_ides(@TempDir Path tmp) throws IOException {
        Path jb = tmp.resolve("JetBrains");
        Files.createDirectories(jb.resolve("PyCharm2025.1").resolve("options"));

        List<Path> touched =
                IntellijSdkRegistrar.of(List.of(jb, tmp.resolve("Google"))).register(List.of(T25));

        assertThat(touched).isEmpty();
        assertThat(Files.exists(jb.resolve("PyCharm2025.1/options/jdk.table.xml")))
                .isFalse();
    }

    @Test
    void writes_android_studio_under_google(@TempDir Path tmp) throws IOException {
        Path google = tmp.resolve("Google");
        Files.createDirectories(google.resolve("AndroidStudio2025.1").resolve("options"));

        List<Path> touched = IntellijSdkRegistrar.of(List.of(tmp.resolve("JetBrains"), google))
                .register(List.of(T25));

        assertThat(touched).hasSize(1);
        assertThat(Files.readString(google.resolve("AndroidStudio2025.1/options/jdk.table.xml")))
                .contains("jk-temurin-25");
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            c++;
            i += sub.length();
        }
        return c;
    }
}
