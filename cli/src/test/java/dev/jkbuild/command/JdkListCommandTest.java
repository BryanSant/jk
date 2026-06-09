// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.command.JdkListCommand.Row;
import dev.jkbuild.command.JdkListCommand.Status;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkVendor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdkListCommandTest {

    @Test
    void marks_path_javac_as_current_and_keeps_default_on_a_different_row(@TempDir Path dir) {
        Path currentHome = dir.resolve("temurin-25.0.1");
        Path defaultHome = dir.resolve("corretto-21.0.5");
        List<JdkHit> installed = List.of(
                new JdkHit(currentHome, "25.0.1", JdkVendor.UNKNOWN, "sdkman"),
                new JdkHit(defaultHome, "21.0.5", JdkVendor.UNKNOWN, "jk"));

        List<Row> rows = JdkListCommand.buildRows(
                installed, "corretto-21.0.5", null, "linux", "x64", currentHome);

        assertThat(rowFor(rows, "temurin-25.0.1").status()).isEqualTo(Status.CURRENT);
        assertThat(rowFor(rows, "corretto-21.0.5").status()).isEqualTo(Status.DEFAULT);
    }

    @Test
    void current_takes_precedence_when_default_and_current_are_the_same_jdk(@TempDir Path dir) {
        Path home = dir.resolve("temurin-25.0.1");
        List<JdkHit> installed = List.of(
                new JdkHit(home, "25.0.1", JdkVendor.UNKNOWN, "sdkman"));

        List<Row> rows = JdkListCommand.buildRows(
                installed, "temurin-25.0.1", null, "linux", "x64", home);

        assertThat(rowFor(rows, "temurin-25.0.1").status()).isEqualTo(Status.CURRENT);
        assertThat(rows).noneMatch(r -> r.status() == Status.DEFAULT);
    }

    @Test
    void synthesizes_a_current_row_when_path_javac_is_not_in_the_list(@TempDir Path dir) throws IOException {
        Path listed = dir.resolve("corretto-21.0.5");
        Path currentHome = dir.resolve("temurin-25.0.1");
        makeJdkInstall(currentHome, "25.0.1");          // a real JDK dir on PATH but unknown to probes

        List<JdkHit> installed = List.of(
                new JdkHit(listed, "21.0.5", JdkVendor.UNKNOWN, "jk"));

        List<Row> rows = JdkListCommand.buildRows(
                installed, null, null, "linux", "x64", currentHome);

        Row current = rowFor(rows, "temurin-25.0.1");
        assertThat(current.status()).isEqualTo(Status.CURRENT);
        assertThat(current.location()).isEqualTo("path");   // synthesized rows are attributed to PATH
    }

    @Test
    void no_current_row_when_path_has_no_javac(@TempDir Path dir) {
        List<JdkHit> installed = List.of(
                new JdkHit(dir.resolve("temurin-25.0.1"), "25.0.1", JdkVendor.UNKNOWN, "sdkman"));

        List<Row> rows = JdkListCommand.buildRows(
                installed, null, null, "linux", "x64", null);

        assertThat(rows).noneMatch(r -> r.status() == Status.CURRENT);
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
        Files.writeString(home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
    }
}
