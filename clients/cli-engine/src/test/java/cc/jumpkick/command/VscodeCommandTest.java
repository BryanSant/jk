// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage for {@code jk vscode} — the Eclipse project metadata + {@code .vscode/}
 * settings the redhat.java language server consumes. Uses {@code --jdks-dir}/{@code --cache-dir}
 * overrides so nothing touches the developer's real {@code ~/.jk}.
 */
class VscodeCommandTest {

    @Test
    void workspace_sibling_becomes_a_source_project(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 25

                [workspace]
                modules = ["lib", "app"]
                """);

        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib.resolve("src/main/java/lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "libcore"
                version = "0.1.0"
                jdk = 25
                """);
        Files.writeString(lib.resolve("src/main/java/lib/Lib.java"), "package lib; public class Lib {}");

        Path app = tmp.resolve("app");
        Files.createDirectories(app.resolve("src/main/java/app"));
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 25

                [dependencies]
                libcore.workspace = true
                """);
        Files.writeString(app.resolve("src/main/java/app/Main.java"), "package app; public class Main {}");

        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");

        assertThat(runVscode(tmp, jdks, tmp.resolve("cache"))).isEqualTo(0);

        // Each module gets its own Eclipse project named after project().name().
        assertThat(Files.readString(lib.resolve(".project"))).contains("<name>libcore</name>");
        assertThat(Files.readString(app.resolve(".project"))).contains("<name>app</name>");

        // app depends on libcore as a live SOURCE project, not a jar.
        String appCp = Files.readString(app.resolve(".classpath"));
        assertThat(appCp).contains("kind=\"src\" path=\"/libcore\"");
        // Source folder output is isolated under target/jdt — never jk's target/classes.
        assertThat(appCp).contains("path=\"src/main/java\" output=\"target/jdt/classes/main\"");
        assertThat(appCp).doesNotContain("target/classes");
        // JRE container bound to the module's execution environment.
        assertThat(appCp).contains("JRE_CONTAINER").contains("JavaSE-25");
    }

    @Test
    void settings_declare_stable_runtime_and_prefs(@TempDir Path tmp) throws IOException {
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

        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");

        assertThat(runVscode(ws, jdks, tmp.resolve("cache"))).isEqualTo(0);

        // settings.json: JDK runtime keyed by execution-environment name (not "temurin-25"),
        // language server home set, and the build-tool importers disabled.
        String settings = Files.readString(ws.resolve(".vscode/settings.json"));
        assertThat(settings)
                .contains("\"name\": \"JavaSE-25\"")
                .contains("temurin-25")
                .contains("\"default\": true")
                .contains("\"java.jdt.ls.java.home\"")
                .contains("\"java.import.maven.enabled\": false")
                .contains("\"java.import.gradle.enabled\": false");

        // Per-module compliance prefs + source folder output under target/jdt.
        assertThat(Files.readString(ws.resolve(".settings/org.eclipse.jdt.core.prefs")))
                .contains("org.eclipse.jdt.core.compiler.compliance=25");
        assertThat(Files.readString(ws.resolve(".classpath")))
                .contains("path=\"src/main/java\" output=\"target/jdt/classes/main\"");

        // extensions.json recommends the Java stack.
        assertThat(Files.readString(ws.resolve(".vscode/extensions.json")))
                .contains("redhat.java")
                .contains("vscjava.vscode-java-debug");

        // The stable pointer resolves to the installed patch.
        assertThat(jdks.resolve("temurin-25").toRealPath())
                .isEqualTo(jdks.resolve("temurin-25.0.3").toRealPath());
    }

    @Test
    void per_module_jdk_level_drives_ee_and_prefs(@TempDir Path tmp) throws IOException {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("jk.toml"), """
                [project]
                group = "dev.example"
                name = "root"
                version = "0.1.0"
                jdk = 25

                [workspace]
                modules = ["a", "b"]
                """);
        module(ws.resolve("a"), "a", 25);
        module(ws.resolve("b"), "b", 21);

        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        fakeJdk(jdks, "temurin-21.0.5", "21.0.5");

        assertThat(runVscode(ws, jdks, tmp.resolve("cache"))).isEqualTo(0);

        // The off-level module gets its own EE + compliance.
        assertThat(Files.readString(ws.resolve("b/.classpath"))).contains("JavaSE-21");
        assertThat(Files.readString(ws.resolve("b/.settings/org.eclipse.jdt.core.prefs")))
                .contains("org.eclipse.jdt.core.compiler.compliance=21");
        assertThat(Files.readString(ws.resolve("a/.classpath"))).contains("JavaSE-25");

        // Both runtimes declared; project default is the root's level.
        String settings = Files.readString(ws.resolve(".vscode/settings.json"));
        assertThat(settings).contains("\"name\": \"JavaSE-25\"").contains("\"name\": \"JavaSE-21\"");
    }

    @Test
    void processor_dep_is_not_a_classpath_lib(@TempDir Path tmp) throws IOException {
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

        String hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Files.writeString(ws.resolve("jk.lock"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                jdk = "temurin-25.0.3"

                [[artifact]]
                name = "org.example:myprocessor"
                version = "1.0.0"
                source = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:%s"
                scopes = ["processor"]
                """.formatted(hex));

        Path cache = tmp.resolve("cache");
        Path casJar = cache.resolve("sha256")
                .resolve(hex.substring(0, 2))
                .resolve(hex.substring(2, 4))
                .resolve(hex.substring(4));
        Files.createDirectories(casJar.getParent());
        Files.writeString(casJar, "dummy-jar");

        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");

        assertThat(runVscode(ws, jdks, cache)).isEqualTo(0);

        String cp = Files.readString(ws.resolve(".classpath"));
        // Processor-only dep is NOT a compile lib entry...
        assertThat(cp).doesNotContain("myprocessor");
        // ...but the generated-sources root is present.
        assertThat(cp).contains("target/generated/sources/annotations/main");
    }

    @Test
    void launch_config_for_a_main_module(@TempDir Path tmp) throws IOException {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws.resolve("src/main/java/app"));
        Files.writeString(ws.resolve("src/main/java/app/Main.java"), "package app; public class Main {}");
        Files.writeString(ws.resolve("jk.toml"), """
                [project]
                group = "dev.example"
                name = "app"
                version = "0.1.0"
                jdk = 25

                [application]
                main = "app.Main"
                """);

        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");

        assertThat(runVscode(ws, jdks, tmp.resolve("cache"))).isEqualTo(0);

        String launch = Files.readString(ws.resolve(".vscode/launch.json"));
        assertThat(launch)
                .contains("\"type\": \"java\"")
                .contains("\"mainClass\": \"app.Main\"")
                .contains("\"projectName\": \"app\"");
    }

    private static int runVscode(Path ws, Path jdks, Path cache) {
        return Jk.execute(new String[] {
            "vscode", "-C", ws.toString(), "--cache-dir", cache.toString(), "--jdks-dir", jdks.toString()
        });
    }

    private static void fakeJdk(Path jdksRoot, String name, String version) throws IOException {
        Path home = jdksRoot.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(
                home.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"" + version + "\"\n");
    }

    private static void module(Path dir, String name, int jdk) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "dev.example"
                name = "%s"
                version = "0.1.0"
                jdk = %d
                """.formatted(name, jdk));
    }
}
