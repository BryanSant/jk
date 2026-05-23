// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SdkmanProbeTest {

    @Test
    void finds_jdk_via_sdkman_identifier(@TempDir Path tempDir) throws Exception {
        Path sdkman = tempDir.resolve(".sdkman");
        Path candidate = sdkman.resolve("candidates").resolve("java").resolve("21.0.5-tem");
        Files.createDirectories(candidate);
        ToolHealthTest.jdkLayout(candidate.getParent(), "21.0.5", "Eclipse Adoptium");
        // Move the synthetic layout to the SDKMAN-named path.
        Path synthetic = candidate.getParent().resolve("jdk-21.0.5");
        moveContents(synthetic, candidate);

        Optional<DiscoveredTool> hit = new SdkmanProbe(sdkman)
                .find(ToolSpec.jdk("21.0.5", "tem"));
        assertThat(hit).isPresent();
        assertThat(hit.get().home()).isEqualTo(candidate.toRealPath());
        assertThat(hit.get().source()).isEqualTo("sdkman");
    }

    @Test
    void misses_when_version_does_not_match(@TempDir Path tempDir) throws Exception {
        Path sdkman = tempDir.resolve(".sdkman");
        Path candidate = sdkman.resolve("candidates").resolve("maven").resolve("3.9.15");
        Files.createDirectories(candidate);
        ToolHealthTest.mavenLayout(candidate.getParent(), "3.9.15");
        moveContents(candidate.getParent().resolve("apache-maven-3.9.15"), candidate);

        // We ask for 3.9.9 — the wrong version. Probe must not match.
        assertThat(new SdkmanProbe(sdkman).find(ToolSpec.maven("3.9.9"))).isEmpty();
    }

    @Test
    void finds_kotlin_by_exact_version(@TempDir Path tempDir) throws Exception {
        Path sdkman = tempDir.resolve(".sdkman");
        Path candidate = sdkman.resolve("candidates").resolve("kotlin").resolve("2.3.21");
        Files.createDirectories(candidate);
        ToolHealthTest.kotlinLayout(candidate.getParent(), "2.3.21-release-298");
        moveContents(candidate.getParent().resolve("kotlinc"), candidate);

        Optional<DiscoveredTool> hit = new SdkmanProbe(sdkman).find(ToolSpec.kotlin("2.3.21"));
        assertThat(hit).isPresent();
        assertThat(hit.get().home()).isEqualTo(candidate.toRealPath());
    }

    @Test
    void user_pinned_tool_already_in_sdkman_resolves_without_download(@TempDir Path tempDir) throws Exception {
        // Open Q #4: user has .jk-version pinning 21.0.5-tem; SDKMAN has it.
        // Provisioner should link straight from SDKMAN, no download attempt.
        Path sdkman = tempDir.resolve(".sdkman");
        Path candidate = sdkman.resolve("candidates").resolve("java").resolve("21.0.5-tem");
        Files.createDirectories(candidate);
        ToolHealthTest.jdkLayout(candidate.getParent(), "21.0.5", "Eclipse Adoptium");
        moveContents(candidate.getParent().resolve("jdk-21.0.5"), candidate);

        Optional<DiscoveredTool> hit = new ToolProvisioner(
                List.of(new SdkmanProbe(sdkman)))
                .discover(ToolSpec.jdk("21.0.5", "tem"));
        assertThat(hit).isPresent();
    }

    /** Move every entry under {@code src} into {@code dst}, replacing dst. */
    private static void moveContents(Path src, Path dst) throws Exception {
        Files.createDirectories(dst);
        try (var stream = Files.walk(src)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.equals(src)) continue;
                Path target = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        try (var stream = Files.walk(src)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }
}
