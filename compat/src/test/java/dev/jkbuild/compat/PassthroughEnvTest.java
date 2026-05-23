// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PassthroughEnvTest {

    @Test
    void strips_overrides_and_inherits_rest() {
        Map<String, String> env = new HashMap<>();
        env.put("JAVA_TOOL_OPTIONS", "-Xmx512m");
        env.put("_JAVA_OPTIONS", "-Dfoo=bar");
        env.put("MAVEN_OPTS", "-Xmx2g");
        env.put("GRADLE_OPTS", "-Dorg.gradle.daemon=false");
        env.put("KOTLIN_HOME", "/opt/kotlin");
        env.put("PATH", "/usr/bin:/bin");
        env.put("HOME", "/home/alice");

        PassthroughEnv.apply(env, null);

        assertThat(env).doesNotContainKeys(
                "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "MAVEN_OPTS", "GRADLE_OPTS", "KOTLIN_HOME");
        assertThat(env).containsEntry("PATH", "/usr/bin:/bin");
        assertThat(env).containsEntry("HOME", "/home/alice");
    }

    @Test
    void sets_java_home_and_prepends_bin_to_path(@TempDir Path tempDir) {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin");

        PassthroughEnv.apply(env, tempDir);

        assertThat(env.get("JAVA_HOME")).isEqualTo(tempDir.toAbsolutePath().toString());
        assertThat(env.get("PATH"))
                .startsWith(tempDir.resolve("bin").toAbsolutePath().toString())
                .endsWith("/usr/bin");
    }

    @Test
    void null_java_home_leaves_path_alone() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin");

        PassthroughEnv.apply(env, null);

        assertThat(env).doesNotContainKey("JAVA_HOME");
        assertThat(env).containsEntry("PATH", "/usr/bin");
    }
}
