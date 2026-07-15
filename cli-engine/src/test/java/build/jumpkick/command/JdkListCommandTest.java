// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.command.JdkListCommand.Row;
import build.jumpkick.command.JdkListCommand.Status;
import build.jumpkick.jdk.JdkHit;
import build.jumpkick.jdk.JdkVendor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkListCommandTest {

    @Test
    void marks_path_javac_as_current_and_keeps_default_on_a_different_row(@TempDir Path dir) {
        Path currentHome = dir.resolve("temurin-25.0.1");
        Path defaultHome = dir.resolve("corretto-21.0.5");
        List<JdkHit> installed = List.of(
                new JdkHit(currentHome, "25.0.1", JdkVendor.UNKNOWN, "sdkman"),
                new JdkHit(defaultHome, "21.0.5", JdkVendor.UNKNOWN, "jk"));

        List<Row> rows = JdkListCommand.buildRows(installed, defaultHome, null, "linux", "x64", currentHome, null);

        assertThat(rowFor(rows, "temurin-25.0.1").status()).isEqualTo(Status.ACTIVE);
        assertThat(rowFor(rows, "corretto-21.0.5").status()).isEqualTo(Status.DEFAULT);
    }

    @Test
    void default_is_disambiguated_by_home_when_two_installs_share_an_identifier(@TempDir Path dir) {
        // Same vendor-major identifier ("temurin-25.0.3") under two roots — only
        // the install whose home matches the recorded default is marked DEFAULT.
        Path jkHome = dir.resolve("jk/temurin-25.0.3");
        Path ideHome = dir.resolve("ide/temurin-25.0.3");
        List<JdkHit> installed = List.of(
                new JdkHit(jkHome, "25.0.3", JdkVendor.TEMURIN, "jk"),
                new JdkHit(ideHome, "25.0.3", JdkVendor.TEMURIN, "intellij"));

        List<Row> rows = JdkListCommand.buildRows(installed, jkHome, null, "linux", "x64", null, null);

        List<Row> defaults =
                rows.stream().filter(r -> r.status() == Status.DEFAULT).toList();
        assertThat(defaults).hasSize(1);
        assertThat(defaults.getFirst().location()).isEqualTo("jk");
    }

    @Test
    void current_takes_precedence_when_default_and_current_are_the_same_jdk(@TempDir Path dir) {
        Path home = dir.resolve("temurin-25.0.1");
        List<JdkHit> installed = List.of(new JdkHit(home, "25.0.1", JdkVendor.UNKNOWN, "sdkman"));

        List<Row> rows = JdkListCommand.buildRows(installed, home, null, "linux", "x64", home, null);

        assertThat(rowFor(rows, "temurin-25.0.1").status()).isEqualTo(Status.ACTIVE);
        assertThat(rows).noneMatch(r -> r.status() == Status.DEFAULT);
    }

    @Test
    void synthesizes_a_current_row_when_path_javac_is_not_in_the_list(@TempDir Path dir) throws IOException {
        Path listed = dir.resolve("corretto-21.0.5");
        Path currentHome = dir.resolve("temurin-25.0.1");
        makeJdkInstall(currentHome, "25.0.1"); // a real JDK dir on PATH but unknown to probes

        List<JdkHit> installed = List.of(new JdkHit(listed, "21.0.5", JdkVendor.UNKNOWN, "jk"));

        List<Row> rows = JdkListCommand.buildRows(installed, null, null, "linux", "x64", currentHome, null);

        Row current = rowFor(rows, "temurin-25.0.1");
        assertThat(current.status()).isEqualTo(Status.ACTIVE);
        assertThat(current.location()).isEqualTo("path"); // synthesized rows are attributed to PATH
    }

    @Test
    void no_current_row_when_path_has_no_javac(@TempDir Path dir) {
        List<JdkHit> installed =
                List.of(new JdkHit(dir.resolve("temurin-25.0.1"), "25.0.1", JdkVendor.UNKNOWN, "sdkman"));

        List<Row> rows = JdkListCommand.buildRows(installed, null, null, "linux", "x64", null, null);

        assertThat(rows).noneMatch(r -> r.status() == Status.ACTIVE);
    }

    private static Row rowFor(List<Row> rows, String spec) {
        return rows.stream()
                .filter(r -> r.spec().equals(spec))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + spec + " in " + rows));
    }

    private static void makeJdkInstall(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
    }
}
