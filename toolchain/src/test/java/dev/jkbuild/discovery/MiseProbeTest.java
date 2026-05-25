// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkVendor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MiseProbeTest {

    @Test
    void find_returns_jdk_when_version_matches(@TempDir Path tempDir) throws Exception {
        Path mise = tempDir.resolve(".local").resolve("share").resolve("mise");
        Path candidate = mise.resolve("installs").resolve("java").resolve("temurin-21.0.5");
        Files.createDirectories(candidate);
        ToolHealthTest.jdkLayout(candidate.getParent(), "21.0.5", "Eclipse Adoptium");
        moveContents(candidate.getParent().resolve("jdk-21.0.5"), candidate);

        Optional<DiscoveredTool> hit = new MiseProbe(mise).find(ToolSpec.jdk("21.0.5", "tem"));
        assertThat(hit).isPresent();
        assertThat(hit.get().home()).isEqualTo(candidate.toRealPath());
        assertThat(hit.get().source()).isEqualTo("mise");
    }

    @Test
    void discover_all_jdks_lists_every_install_with_vendor(@TempDir Path tempDir) throws Exception {
        Path mise = tempDir.resolve(".local").resolve("share").resolve("mise");
        Path javaDir = mise.resolve("installs").resolve("java");
        Files.createDirectories(javaDir);

        Path temurin = javaDir.resolve("temurin-21.0.5");
        Files.createDirectories(temurin);
        ToolHealthTest.jdkLayout(javaDir, "21.0.5", "Eclipse Adoptium");
        moveContents(javaDir.resolve("jdk-21.0.5"), temurin);

        Path corretto = javaDir.resolve("corretto-17.0.10");
        Files.createDirectories(corretto);
        ToolHealthTest.jdkLayout(javaDir, "17.0.10", "Amazon.com Inc.");
        moveContents(javaDir.resolve("jdk-17.0.10"), corretto);

        List<JdkHit> hits = new MiseProbe(mise).discoverAllJdks();
        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(JdkHit::vendor)
                .containsExactlyInAnyOrder(JdkVendor.TEMURIN, JdkVendor.CORRETTO);
        assertThat(hits).extracting(JdkHit::source).containsOnly("mise");
    }

    @Test
    void discover_skips_the_latest_symlink(@TempDir Path tempDir) throws Exception {
        Path mise = tempDir.resolve("mise");
        Path javaDir = mise.resolve("installs").resolve("java");
        Files.createDirectories(javaDir);

        Path real = javaDir.resolve("temurin-21.0.5");
        Files.createDirectories(real);
        ToolHealthTest.jdkLayout(javaDir, "21.0.5", "Eclipse Adoptium");
        moveContents(javaDir.resolve("jdk-21.0.5"), real);

        // mise sometimes maintains a `latest` symlink to the active version.
        // We must skip it so a single install doesn't appear twice in discover output.
        Files.createSymbolicLink(javaDir.resolve("latest"), real);

        List<JdkHit> hits = new MiseProbe(mise).discoverAllJdks();
        assertThat(hits).hasSize(1);
    }

    @Test
    void discover_returns_empty_when_root_absent(@TempDir Path tempDir) throws Exception {
        // Fail-fast: no installs dir → empty list, no syscall storm.
        assertThat(new MiseProbe(tempDir.resolve("does-not-exist")).discoverAllJdks()).isEmpty();
    }

    @Test
    void data_dir_precedence_mise_env_overrides_xdg_and_default() {
        Path explicit = Path.of("/explicit/mise");
        Path xdg = Path.of("/xdg/share");
        Map<String, String> env = Map.of(
                "MISE_DATA_DIR", explicit.toString(),
                "XDG_DATA_HOME", xdg.toString());
        assertThat(MiseProbe.resolveDataDir(env::get, "/home/u")).isEqualTo(explicit);
    }

    @Test
    void data_dir_precedence_xdg_overrides_default() {
        Path xdg = Path.of("/xdg/share");
        Map<String, String> env = Map.of("XDG_DATA_HOME", xdg.toString());
        assertThat(MiseProbe.resolveDataDir(env::get, "/home/u")).isEqualTo(xdg.resolve("mise"));
    }

    @Test
    void data_dir_falls_back_to_user_home() {
        assertThat(MiseProbe.resolveDataDir(name -> null, "/home/u"))
                .isEqualTo(Path.of("/home/u/.local/share/mise"));
    }

    /** Move every entry under {@code src} into {@code dst}. Mirrors SdkmanProbeTest's helper. */
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
