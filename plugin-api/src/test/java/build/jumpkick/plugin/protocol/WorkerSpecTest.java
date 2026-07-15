// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.plugin.PluginConfig;
import build.jumpkick.plugin.build.PackageIo;
import build.jumpkick.plugin.build.ProjectFacts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The unified spec writer and reader round-trip every line type without loss. */
class WorkerSpecTest {

    @Test
    void round_trips_the_spec_vocabulary(@TempDir Path dir) throws Exception {
        SpecWriter w = new SpecWriter()
                .op(WorkerProtocol.OP_COMPILE, "compile-java", "jk-java-compiler")
                .configString("moduleName", "widget")
                .configBool("incremental", true)
                .configInt("release", 25)
                .configList("flags", List.of("-parameters", "-g"))
                .project(new ProjectFacts("com.example", "widget", "1.2.3", 25, "com.example.Main", true, false,
                        Map.of("Built-By", "jk")))
                .layout(Map.of("classesDir", dir.resolve("classes"), "moduleDir", dir))
                .javaHome(dir.resolve("jdk"))
                .artifact(dir.resolve("widget.jar"))
                .cp(dir.resolve("dep.jar"), WorkerProtocol.ROLE_COMPILE)
                .cp(dir.resolve("proc.jar"), WorkerProtocol.ROLE_PROCESSOR)
                .cp(dir.resolve("friend.jar"), WorkerProtocol.ROLE_FRIEND)
                .entry("dep-1.0.jar", dir.resolve("dep.jar"), false, null)
                .entry("snap-2.0.jar", dir.resolve("snap.jar"), true, null)
                .source(dir.resolve("Main.java"))
                .arg("-Xlint:all")
                .compilerPlugin("org.jetbrains.kotlin.allopen", dir.resolve("allopen.jar"), List.of("annotation=X"))
                .stepOutput("android-res", dir.resolve("res"))
                .extra("protoc", dir.resolve("protoc"))
                .secret("gpgPassphrase", "hunter2")
                .commandArgs(List.of("--fast"));

        Path spec = dir.resolve("worker.spec");
        Files.write(spec, w.lines(), StandardCharsets.UTF_8);
        WorkerSpec s = WorkerSpec.read(spec);

        assertThat(s.op()).isEqualTo(WorkerProtocol.OP_COMPILE);
        assertThat(s.name()).contains("compile-java");
        assertThat(s.pluginId()).isEqualTo("jk-java-compiler");

        PluginConfig c = s.config();
        assertThat(c.string("moduleName")).isEqualTo("widget");
        assertThat(c.bool("incremental", false)).isTrue();
        assertThat(c.intValue("release", 0)).isEqualTo(25);
        assertThat(c.stringList("flags")).containsExactly("-parameters", "-g");

        ProjectFacts p = s.project();
        assertThat(p.group()).isEqualTo("com.example");
        assertThat(p.name()).isEqualTo("widget");
        assertThat(p.version()).isEqualTo("1.2.3");
        assertThat(p.javaRelease()).isEqualTo(25);
        assertThat(p.mainClass()).isEqualTo("com.example.Main");
        assertThat(p.nativeDeclared()).isTrue();
        assertThat(p.manifest()).containsEntry("Built-By", "jk");

        assertThat(s.classesDir()).isEqualTo(dir.resolve("classes").toAbsolutePath());
        assertThat(s.moduleDir()).isEqualTo(dir.toAbsolutePath());
        assertThat(s.javaHome()).isEqualTo(dir.resolve("jdk").toAbsolutePath());
        assertThat(s.artifactPath()).isEqualTo(dir.resolve("widget.jar").toAbsolutePath());

        assertThat(s.compileClasspath()).containsExactly(dir.resolve("dep.jar").toAbsolutePath());
        assertThat(s.processorClasspath()).containsExactly(dir.resolve("proc.jar").toAbsolutePath());
        assertThat(s.friendPaths()).containsExactly(dir.resolve("friend.jar").toAbsolutePath());

        assertThat(s.entries()).extracting(PackageIo.RuntimeEntry::fileName)
                .containsExactly("dep-1.0.jar", "snap-2.0.jar");
        assertThat(s.entries()).extracting(PackageIo.RuntimeEntry::snapshot).containsExactly(false, true);

        assertThat(s.sources()).containsExactly(dir.resolve("Main.java").toAbsolutePath());
        assertThat(s.args()).containsExactly("-Xlint:all");
        assertThat(s.compilerPlugins()).singleElement()
                .satisfies(cp -> {
                    assertThat(cp.id()).isEqualTo("org.jetbrains.kotlin.allopen");
                    assertThat(cp.options()).containsExactly("annotation=X");
                });
        assertThat(s.stepOutput("android-res")).contains(dir.resolve("res").toAbsolutePath());
        assertThat(s.extra("protoc")).contains(dir.resolve("protoc").toAbsolutePath());
        assertThat(s.secret("gpgPassphrase")).contains("hunter2");
        assertThat(s.commandArgs()).containsExactly("--fast");
    }
}
