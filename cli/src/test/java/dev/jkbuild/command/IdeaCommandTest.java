// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for {@code jk idea}'s JDK/SDK wiring and source roots.
 * Uses {@code --jdks-dir}/{@code --ide-config-dir} overrides so nothing touches
 * the developer's real {@code ~/.jk} or IntelliJ config.
 */
class IdeaCommandTest {

    private static void fakeJdk(Path jdksRoot, String name, String version) throws IOException {
        Path home = jdksRoot.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(home.resolve("release"),
                "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"" + version + "\"\n");
    }

    private static int runIdea(Path ws, Path jdks, Path ideConfig, Path cache) {
        return Jk.execute(new String[] {
                "idea", "-C", ws.toString(),
                "--cache-dir", cache.toString(),
                "--jdks-dir", jdks.toString(),
                "--ide-config-dir", ideConfig.toString()});
    }

    @Test
    void registers_stable_sdk_and_marks_resource_roots(@TempDir Path tmp) throws IOException {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("jk.toml"), """
                [project]
                group = "dev.example"
                name = "widget"
                version = "0.1.0"
                jdk = 25
                """);
        // Traditional layout with a main source root and a main resource root.
        Files.createDirectories(ws.resolve("src/main/java/example"));
        Files.writeString(ws.resolve("src/main/java/example/Hello.java"),
                "package example;\npublic class Hello {}\n");
        Files.createDirectories(ws.resolve("src/main/resources"));
        Files.writeString(ws.resolve("src/main/resources/app.properties"), "k=v\n");

        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");

        Path ideConfig = tmp.resolve("ideconfig");
        Files.createDirectories(ideConfig.resolve("JetBrains/IntelliJIdea2025.1/options"));

        int exit = runIdea(ws, jdks, ideConfig, tmp.resolve("cache"));
        assertThat(exit).isEqualTo(0);

        // #1 — misc.xml references the stable, jk-managed SDK name (not "25").
        String misc = Files.readString(ws.resolve(".idea/misc.xml"));
        assertThat(misc).contains("project-jdk-name=\"jk-temurin-25\"")
                .contains("languageLevel=\"JDK_25\"");

        // #1 — the SDK is actually defined in the IDE's jdk.table.xml.
        Path table = ideConfig.resolve("JetBrains/IntelliJIdea2025.1/options/jdk.table.xml");
        assertThat(table).exists();
        assertThat(Files.readString(table)).contains("jk-temurin-25").contains("JavaSDK");

        // The stable pointer resolves to the installed patch.
        Path pointer = jdks.resolve("temurin-25");
        assertThat(Files.exists(pointer)).isTrue();
        assertThat(pointer.toRealPath()).isEqualTo(jdks.resolve("temurin-25.0.3").toRealPath());

        // #3 — resource root marked, source root present, single inherited JDK.
        String iml = Files.readString(ws.resolve("widget.iml"));
        assertThat(iml).contains("<orderEntry type=\"inheritedJdk\" />")
                .contains("src/main/java")
                .contains("url=\"file://$MODULE_DIR$/src/main/resources\" type=\"java-resource\"");
    }

    @Test
    void per_module_jdk_when_a_member_differs_from_the_project_default(@TempDir Path tmp)
            throws IOException {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("jk.toml"), """
                [project]
                group = "dev.example"
                name = "root"
                version = "0.1.0"
                jdk = 25

                [workspace]
                members = ["a", "b"]
                """);
        member(ws.resolve("a"), "a", 25);
        member(ws.resolve("b"), "b", 21);   // differs from the project default (25)

        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        fakeJdk(jdks, "temurin-21.0.5", "21.0.5");

        Path ideConfig = tmp.resolve("ideconfig");
        Files.createDirectories(ideConfig.resolve("JetBrains/IntelliJIdea2025.1/options"));

        int exit = runIdea(ws, jdks, ideConfig, tmp.resolve("cache"));
        assertThat(exit).isEqualTo(0);

        // Default-level member inherits the project SDK.
        assertThat(Files.readString(ws.resolve("a/a.iml")))
                .contains("<orderEntry type=\"inheritedJdk\" />");

        // #2 — the off-level member gets its own SDK + source language level.
        String bIml = Files.readString(ws.resolve("b/b.iml"));
        assertThat(bIml)
                .contains("LANGUAGE_LEVEL=\"JDK_21\"")
                .contains("<orderEntry type=\"jdk\" jdkName=\"jk-temurin-21\" jdkType=\"JavaSDK\" />");

        // Both SDKs registered; project default is the root's level.
        String table = Files.readString(
                ideConfig.resolve("JetBrains/IntelliJIdea2025.1/options/jdk.table.xml"));
        assertThat(table).contains("jk-temurin-25").contains("jk-temurin-21");
        assertThat(Files.readString(ws.resolve(".idea/misc.xml")))
                .contains("project-jdk-name=\"jk-temurin-25\"");
    }

    @Test
    void sibling_module_dep_is_wired_even_when_the_jar_is_not_built(@TempDir Path tmp)
            throws IOException {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("jk.toml"), """
                [project]
                group = "dev.example"
                name = "root"
                version = "0.1.0"
                jdk = 25

                [workspace]
                members = ["a", "b"]
                """);
        member(ws.resolve("a"), "a", 25);
        // b depends on sibling a — and nothing has been built, so a/target/*.jar
        // does not exist. The IDE module edge must still be emitted.
        Files.createDirectories(ws.resolve("b"));
        Files.writeString(ws.resolve("b/jk.toml"), """
                [project]
                group = "dev.example"
                name = "b"
                version = "0.1.0"
                jdk = 25

                [dependencies.main]
                a = { workspace = true }
                """);

        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        Path ideConfig = tmp.resolve("ideconfig");
        Files.createDirectories(ideConfig.resolve("JetBrains/IntelliJIdea2025.1/options"));

        int exit = runIdea(ws, jdks, ideConfig, tmp.resolve("cache"));
        assertThat(exit).isEqualTo(0);

        // Precondition: a is genuinely unbuilt — no compiled artifact on disk.
        assertThat(Files.exists(ws.resolve("a/target"))).isFalse();
        // Regression: b must declare the module dependency on a regardless, or
        // IntelliJ can't resolve a's classes from b.
        assertThat(Files.readString(ws.resolve("b/b.iml")))
                .contains("<orderEntry type=\"module\" module-name=\"a\" />");
    }

    @Test
    void annotation_processor_is_wired_not_a_compile_dep(@TempDir Path tmp) throws IOException {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws.resolve("src/main/java/example"));
        Files.writeString(ws.resolve("src/main/java/example/Hello.java"),
                "package example;\npublic class Hello {}\n");
        Files.writeString(ws.resolve("jk.toml"), """
                [project]
                group = "dev.example"
                name = "widget"
                version = "0.1.0"
                jdk = 25
                """);

        // A lock with a single processor-scoped dependency.
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

        // Pre-populate the CAS so the processor JAR is "synced".
        Path cache = tmp.resolve("cache");
        Path casJar = cache.resolve("sha256").resolve(hex.substring(0, 2))
                .resolve(hex.substring(2, 4)).resolve(hex.substring(4));
        Files.createDirectories(casJar.getParent());
        Files.writeString(casJar, "dummy-jar");

        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        Path ideConfig = tmp.resolve("ideconfig");
        Files.createDirectories(ideConfig.resolve("JetBrains/IntelliJIdea2025.1/options"));

        int exit = runIdea(ws, jdks, ideConfig, cache);
        assertThat(exit).isEqualTo(0);

        // #4 — processor goes to the annotation-processing profile, not the classpath.
        String compiler = Files.readString(ws.resolve(".idea/compiler.xml"));
        assertThat(compiler)
                .contains("<annotationProcessing>")
                .contains("profile name=\"jk-widget\"")
                .contains("myprocessor-1.0.0.jar")  // repo path with proper Maven name + .jar
                .contains("target/build/generated/sources/annotations/main");

        String iml = Files.readString(ws.resolve("widget.iml"));
        // Generated source root present...
        assertThat(iml).contains(
                "url=\"file://$MODULE_DIR$/target/build/generated/sources/annotations/main\" "
                + "isTestSource=\"false\" generated=\"true\"");
        // ...and the processor is NOT a compile-scoped library order entry.
        assertThat(iml).doesNotContain("org.example:myprocessor:1.0.0");
    }

    private static void member(Path dir, String name, int jdk) throws IOException {
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
