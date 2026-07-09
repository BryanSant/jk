// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.tool;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.model.Coordinate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS) // asserts the POSIX launcher shape
class ToolLauncherKtsTest {

    @Test
    void kts_launcher_execs_kotlinc_script_with_the_dep_classpath(@TempDir Path tmp) throws Exception {
        Path envs = tmp.resolve("envs");
        Path bin = tmp.resolve("bin");
        Path kotlinc = tmp.resolve("kotlinc/bin/kotlinc");
        Path script = tmp.resolve("envs/hello/hello.kts");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "println(1)\n");
        Path dep = tmp.resolve("cas/aa/gson.jar");

        ToolEnv env = new ToolEnv("hello", Coordinate.of("script", "hello", "local"), "kotlin-script", List.of(dep));
        Path launcher = ToolLauncher.installKotlinScript(envs, bin, tmp.resolve("jdk"), kotlinc, script, env);

        assertThat(launcher).isEqualTo(bin.resolve("hello"));
        assertThat(Files.isExecutable(launcher)).isTrue();
        String body = Files.readString(launcher);
        assertThat(body).contains("-script").contains(script.toString());
        assertThat(body).contains("-classpath").contains(dep.toString());
        assertThat(body).contains("\"$@\"");

        String json = Files.readString(envs.resolve("hello/env.json"));
        assertThat(json).contains("\"mainClass\": \"kotlin-script\"");
        assertThat(json).contains(dep.toString()); // deps stay GC-reachable via env.json
    }

    @Test
    void kts_launcher_without_deps_omits_classpath(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("s.kts");
        Files.writeString(script, "println(1)\n");
        ToolEnv env = new ToolEnv("s", Coordinate.of("script", "s", "local"), "kotlin-script", List.of());
        Path launcher = ToolLauncher.installKotlinScript(
                tmp.resolve("envs"), tmp.resolve("bin"), tmp.resolve("jdk"), tmp.resolve("kotlinc"), script, env);
        assertThat(Files.readString(launcher)).doesNotContain("-classpath");
    }
}
