// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.discovery.DiscoveredTool;
import cc.jumpkick.discovery.JkProbe;
import cc.jumpkick.discovery.LocalToolProbe;
import cc.jumpkick.discovery.ToolSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkRegistryTest {

    @Test
    void list_returns_immediate_subdirectories(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5");
        makeJdkInstall(tempDir.resolve("graalvm-ce-21.0.2"), "21.0.2");
        Files.writeString(tempDir.resolve("README.txt"), "ignored");

        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.list())
                .extracting(InstalledJdk::identifier)
                .containsExactlyInAnyOrder("graalvm-ce-21.0.2", "temurin-21.0.5");
    }

    @Test
    void list_skips_directories_without_bin(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("not-a-jdk"));
        assertThat(isolatedRegistry(tempDir).list()).isEmpty();
    }

    @Test
    void list_resolves_macos_contents_home_layout(@TempDir Path tempDir) throws IOException {
        Path bundle = tempDir.resolve("temurin-21");
        Path macHome = bundle.resolve("Contents").resolve("Home");
        Files.createDirectories(macHome.resolve("bin"));
        Files.writeString(macHome.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(macHome.resolve("release"), "JAVA_VERSION=\"21\"\n");

        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.list()).singleElement().satisfies(jdk -> {
            assertThat(jdk.identifier()).isEqualTo("temurin-21");
            assertThat(jdk.home()).isEqualTo(macHome.toRealPath());
        });
    }

    @Test
    void missing_root_returns_empty_list(@TempDir Path tempDir) throws IOException {
        Path nonexistent = tempDir.resolve("nope");
        assertThat(isolatedRegistry(nonexistent).list()).isEmpty();
    }

    @Test
    void find_by_prefix(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5");
        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.findByPrefix("temurin-21"))
                .isPresent()
                .get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("temurin-21.0.5");
    }

    @Test
    void remove_deletes_directory_tree(@TempDir Path tempDir) throws IOException {
        Path jdkHome = tempDir.resolve("temurin-21.0.5");
        makeJdkInstall(jdkHome, "21.0.5");

        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.remove("temurin-21.0.5")).isTrue();
        assertThat(jdkHome).doesNotExist();

        assertThat(registry.remove("not-installed")).isFalse();
    }

    @Test
    void find_by_spec_bare_major(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5", "Eclipse Adoptium");
        makeJdkInstall(tempDir.resolve("corretto-25.0.3"), "25.0.3", "Amazon.com Inc.");
        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.findBySpec("25"))
                .isPresent()
                .get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("corretto-25.0.3");
    }

    @Test
    void find_by_spec_vendor_plus_major(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5", "Eclipse Adoptium");
        makeJdkInstall(tempDir.resolve("temurin-25.0.1"), "25.0.1", "Eclipse Adoptium");
        makeJdkInstall(tempDir.resolve("corretto-25.0.3"), "25.0.3", "Amazon.com Inc.");
        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.findBySpec("temurin-25"))
                .get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("temurin-25.0.1");
    }

    @Test
    void find_by_spec_vendor_plus_exact_version(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("corretto-25.0.2"), "25.0.2", "Amazon.com Inc.");
        makeJdkInstall(tempDir.resolve("corretto-25.0.3"), "25.0.3", "Amazon.com Inc.");
        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.findBySpec("corretto-25.0.3"))
                .get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("corretto-25.0.3");
    }

    @Test
    void find_by_spec_version_then_sdkman_suffix(@TempDir Path tempDir) throws IOException {
        // librca = liberica's SDKMAN suffix.
        makeJdkInstall(tempDir.resolve("liberica-26.0.1"), "26.0.1", "BellSoft");
        makeJdkInstall(tempDir.resolve("temurin-26.0.1"), "26.0.1", "Eclipse Adoptium");
        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.findBySpec("26.0.1-librca"))
                .get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("liberica-26.0.1");
    }

    @Test
    void find_by_spec_returns_empty_when_no_match(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5", "Eclipse Adoptium");
        assertThat(isolatedRegistry(tempDir).findBySpec("corretto-25")).isEmpty();
    }

    @Test
    void find_by_spec_bare_graalvm_hint_matches_installed_oracle_graalvm(@TempDir Path tempDir) throws IOException {
        // [native].graal defaults to "graalvm" — verifies a bare vendor hint finds an
        // already-installed Oracle GraalVM (e.g. via SDKMAN) instead of missing it, the
        // way the old "native" keyword did against JdkSelector's hint matcher.
        makeJdkInstall(tempDir.resolve("temurin-25.0.1"), "25.0.1", "Eclipse Adoptium");
        makeGraalvmInstall(tempDir.resolve("graalvm-jdk-25"), "25");
        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.findBySpec("graalvm"))
                .isPresent()
                .get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("graalvm-jdk-25");
    }

    @Test
    void list_hits_prefers_manager_source_over_java_home(@TempDir Path tempDir) {
        // EnvVarProbe ($JAVA_HOME → "java-home") sits first in the chain, but a
        // manager probe that reports the same home must win the attribution.
        Path home = tempDir.resolve("25.0.3-tem");
        JdkRegistry registry = new JdkRegistry(
                tempDir,
                List.of(
                        fakeProbe("java-home", new JdkHit(home, "25.0.3", JdkVendor.UNKNOWN, "java-home")),
                        fakeProbe("sdkman", new JdkHit(home, "25.0.3", JdkVendor.UNKNOWN, "sdkman"))));

        assertThat(registry.listHits())
                .singleElement()
                .extracting(JdkHit::source)
                .isEqualTo("sdkman");
    }

    @Test
    void list_hits_relabels_java_home_only_install_as_path(@TempDir Path tempDir) {
        // A JDK reachable only via $JAVA_HOME (no manager owns it) is kept, but
        // the ephemeral "java-home" label is replaced with "path".
        Path home = tempDir.resolve("opt-jdk-25");
        JdkRegistry registry = new JdkRegistry(
                tempDir, List.of(fakeProbe("java-home", new JdkHit(home, "25", JdkVendor.UNKNOWN, "java-home"))));

        assertThat(registry.listHits())
                .singleElement()
                .extracting(JdkHit::source)
                .isEqualTo("path");
    }

    @Test
    void list_hits_keeps_intellij_only_for_ide_registered_installs(@TempDir Path tempDir) {
        // Both live in IntelliJ's dir (source "intellij"); only `managed` is in a
        // jdk.table.xml. The other must drop to the removable "jdks" label.
        Path managed = tempDir.resolve("graalvm-ce-24.0.2");
        Path unmanaged = tempDir.resolve("temurin-21.0.5");
        JdkRegistry registry = new JdkRegistry(
                tempDir,
                List.of(
                        fakeProbe("intellij", new JdkHit(managed, "24.0.2", JdkVendor.UNKNOWN, "intellij")),
                        fakeProbe("intellij", new JdkHit(unmanaged, "21.0.5", JdkVendor.UNKNOWN, "intellij"))),
                IntellijJdkTable.ofManaged(Set.of(managed)));

        assertThat(registry.listHits())
                .extracting(JdkHit::home, JdkHit::source)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(managed, "intellij"),
                        org.assertj.core.api.Assertions.tuple(unmanaged, "jdks"));
    }

    @Test
    void find_hit_at_least_treats_full_version_as_a_floor(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-25.0.2"), "25.0.2");
        JdkRegistry registry = isolatedRegistry(tempDir);

        // Older point release does not satisfy a 25.0.3 floor.
        assertThat(registry.findHitAtLeast(25, "25.0.3", List.of())).isEmpty();
        // The install itself satisfies its own version and any lower floor.
        assertThat(registry.findHitAtLeast(25, "25.0.2", List.of())).isPresent();
        assertThat(registry.findHitAtLeast(25, "25.0.1", List.of())).isPresent();
        // Bare-major (null floor) is satisfied by any point release of the major.
        assertThat(registry.findHitAtLeast(25, null, List.of())).isPresent();
        // Different major never matches.
        assertThat(registry.findHitAtLeast(21, null, List.of())).isEmpty();
    }

    @Test
    void find_hit_at_least_accepts_a_newer_point_release(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-25.0.10"), "25.0.10");
        JdkRegistry registry = isolatedRegistry(tempDir);

        // versionKey ordering: 25.0.10 >= 25.0.9 (not a lexical 1 < 9 mistake).
        assertThat(registry.findHitAtLeast(25, "25.0.9", List.of())).isPresent();
        assertThat(registry.findHitAtLeast(25, "25.0.3", List.of())).isPresent();
    }

    @Test
    void managed_hits_returns_all_jk_installs_when_no_spec(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-25.0.2"), "25.0.2");
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5");
        makeJdkInstall(tempDir.resolve("corretto-25.0.1"), "25.0.1", "Amazon.com Inc.");

        assertThat(isolatedRegistry(tempDir).managedHits(null))
                .extracting(h -> JdkRegistry.identifierFor(h.home()))
                .containsExactlyInAnyOrder("temurin-25.0.2", "temurin-21.0.5", "corretto-25.0.1");
    }

    @Test
    void managed_hits_bare_major_matches_across_vendors(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-25.0.2"), "25.0.2");
        makeJdkInstall(tempDir.resolve("corretto-25.0.1"), "25.0.1", "Amazon.com Inc.");
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5");

        assertThat(isolatedRegistry(tempDir).managedHits("25"))
                .extracting(h -> JdkRegistry.identifierFor(h.home()))
                .containsExactlyInAnyOrder("temurin-25.0.2", "corretto-25.0.1");
    }

    @Test
    void managed_hits_vendor_spec_limits_to_that_vendor(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-25.0.2"), "25.0.2");
        makeJdkInstall(tempDir.resolve("corretto-25.0.1"), "25.0.1", "Amazon.com Inc.");

        assertThat(isolatedRegistry(tempDir).managedHits("temurin"))
                .extracting(h -> JdkRegistry.identifierFor(h.home()))
                .containsExactly("temurin-25.0.2");
    }

    /**
     * A {@link JdkRegistry} backed by a single {@link JkProbe} rooted at {@code root} — isolates the
     * test from any JDKs actually installed on the host.
     */
    private static JdkRegistry isolatedRegistry(Path root) {
        return new JdkRegistry(root, List.of(new JkProbe(root)));
    }

    /** A {@link LocalToolProbe} that enumerates exactly the given hits. */
    private static LocalToolProbe fakeProbe(String name, JdkHit... hits) {
        return new LocalToolProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Optional<DiscoveredTool> find(ToolSpec spec) {
                return Optional.empty();
            }

            @Override
            public List<JdkHit> discoverAllJdks() {
                return List.of(hits);
            }
        };
    }

    private static void makeJdkInstall(Path home, String version) throws IOException {
        makeJdkInstall(home, version, "Eclipse Adoptium");
    }

    private static void makeJdkInstall(Path home, String version, String implementor) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"" + implementor + "\"\n");
    }

    private static void makeGraalvmInstall(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(home.resolve("bin").resolve("native-image"), "#!/fake");
        Files.writeString(
                home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Oracle Corporation\"\n"
                        + "IMPLEMENTOR_VERSION=\"Oracle GraalVM " + version + "\"\n");
    }
}
