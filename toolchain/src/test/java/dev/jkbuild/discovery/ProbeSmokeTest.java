// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight smoke tests against the host's real SDKMAN / Homebrew /
 * system layouts. Each test silently passes when the layout isn't
 * present — these are "probe doesn't blow up on real input" checks, not
 * portable assertions. Synthetic-fixture tests live in
 * {@code ProbesTest}.
 */
class ProbeSmokeTest {

    @Test
    void env_probe_finds_current_jdk_when_java_home_matches_its_release() throws Exception {
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv == null || javaHomeEnv.isBlank()) return;
        Path home = Path.of(javaHomeEnv);
        if (!Files.exists(home.resolve("release"))) return;

        Optional<String> version = ToolHealth.detectVersion(
                ToolSpec.jdk("ignored", null), home);
        if (version.isEmpty()) return;

        EnvVarProbe probe = new EnvVarProbe();
        Optional<DiscoveredTool> hit = probe.find(ToolSpec.jdk(version.get(), null));
        assertThat(hit).isPresent();
        assertThat(hit.get().home()).isEqualTo(home.toAbsolutePath().normalize());
        assertThat(hit.get().source()).startsWith("env:");
    }

    @Test
    void sdkman_probe_returns_kotlin_when_kotlin_home_resolves_to_sdkman_install()
            throws Exception {
        String kotlinHome = System.getenv("KOTLIN_HOME");
        if (kotlinHome == null || !kotlinHome.contains(".sdkman")) return;

        Path home = Path.of(kotlinHome).toRealPath();
        Optional<String> version = ToolHealth.detectVersion(ToolSpec.kotlin("ignored"), home);
        if (version.isEmpty()) return;

        Optional<DiscoveredTool> hit = new SdkmanProbe().find(ToolSpec.kotlin(version.get()));
        assertThat(hit).isPresent();
        assertThat(hit.get().home()).isEqualTo(home);
        assertThat(hit.get().source()).isEqualTo("sdkman");
    }
}
