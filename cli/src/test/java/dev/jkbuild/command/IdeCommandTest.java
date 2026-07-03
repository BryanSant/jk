// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.Jk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for the {@code jk ide} front door: by default it generates <b>both</b> IntelliJ and VS
 * Code configs; {@code --idea}/{@code --vscode} narrow it to one.
 */
class IdeCommandTest {

    @Test
    void generates_both_ides_by_default(@TempDir Path tmp) throws IOException {
        Path ws = simpleProject(tmp);
        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        Path ideConfig = ideConfig(tmp);

        assertThat(Jk.execute(new String[] {
                    "ide", "-C", ws.toString(),
                    "--cache-dir", tmp.resolve("cache").toString(),
                    "--jdks-dir", jdks.toString(),
                    "--ide-config-dir", ideConfig.toString()
                }))
                .isEqualTo(0);

        assertThat(ws.resolve(".idea/misc.xml")).exists();
        assertThat(ws.resolve("widget.iml")).exists();
        assertThat(ws.resolve(".vscode/settings.json")).exists();
        assertThat(ws.resolve(".classpath")).exists();
    }

    @Test
    void idea_flag_generates_only_intellij(@TempDir Path tmp) throws IOException {
        Path ws = simpleProject(tmp);
        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        Path ideConfig = ideConfig(tmp);

        assertThat(Jk.execute(new String[] {
                    "ide", "--idea", "-C", ws.toString(),
                    "--cache-dir", tmp.resolve("cache").toString(),
                    "--jdks-dir", jdks.toString(),
                    "--ide-config-dir", ideConfig.toString()
                }))
                .isEqualTo(0);

        assertThat(ws.resolve(".idea/misc.xml")).exists();
        assertThat(Files.exists(ws.resolve(".vscode"))).isFalse();
        assertThat(Files.exists(ws.resolve(".classpath"))).isFalse();
    }

    @Test
    void vscode_flag_generates_only_vscode(@TempDir Path tmp) throws IOException {
        Path ws = simpleProject(tmp);
        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");

        assertThat(Jk.execute(new String[] {
                    "ide", "--vscode", "-C", ws.toString(),
                    "--cache-dir", tmp.resolve("cache").toString(),
                    "--jdks-dir", jdks.toString()
                }))
                .isEqualTo(0);

        assertThat(ws.resolve(".vscode/settings.json")).exists();
        assertThat(Files.exists(ws.resolve(".idea"))).isFalse();
    }

    private static Path simpleProject(Path tmp) throws IOException {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws.resolve("src/main/java/example"));
        Files.writeString(ws.resolve("src/main/java/example/Hello.java"), "package example;\npublic class Hello {}\n");
        Files.writeString(ws.resolve("jk.toml"), """
                [project]
                group = "dev.example"
                name = "widget"
                version = "0.1.0"
                jdk = 25
                """);
        return ws;
    }

    private static Path ideConfig(Path tmp) throws IOException {
        Path ideConfig = tmp.resolve("ideconfig");
        Files.createDirectories(ideConfig.resolve("JetBrains/IntelliJIdea2025.1/options"));
        return ideConfig;
    }

    private static void fakeJdk(Path jdksRoot, String name, String version) throws IOException {
        Path home = jdksRoot.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(
                home.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"" + version + "\"\n");
    }
}
