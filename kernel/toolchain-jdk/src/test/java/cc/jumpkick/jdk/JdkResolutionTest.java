// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.discovery.JkProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkResolutionTest {

    private static final int LATEST_LTS = 25;

    @Test
    void switch_wins_over_everything(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        makeJdk(jdks, "temurin-21.0.5");
        makeJdk(jdks, "temurin-25.0.3");
        var req = req(tmp).switchSpec("21").projectJdkSpec("25").build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.SWITCH);
        assertThat(r.jdk().get().home().getFileName().toString()).isEqualTo("temurin-21.0.5");
    }

    @Test
    void jdk_version_file_beats_lock_and_toml(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        makeJdk(jdks, "temurin-21.0.5");
        makeJdk(jdks, "temurin-25.0.3");
        Files.writeString(tmp.resolve(".jdk-version"), "temurin-25");
        var req = req(tmp).lockJdkId("temurin-21.0.5").projectJdkSpec("21").build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.JDK_VERSION_FILE);
        assertThat(r.jdk().get().home().getFileName().toString()).isEqualTo("temurin-25.0.3");
    }

    @Test
    void lock_beats_toml(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        makeJdk(jdks, "temurin-21.0.5");
        makeJdk(jdks, "temurin-25.0.3");
        var req = req(tmp).lockJdkId("temurin-21.0.5").projectJdkSpec("25").build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.LOCKFILE);
        assertThat(r.jdk().get().home().getFileName().toString()).isEqualTo("temurin-21.0.5");
    }

    @Test
    void current_beats_default(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        Path j21 = makeJdk(jdks, "temurin-21.0.5");
        Path j25 = makeJdk(jdks, "temurin-25.0.3");
        GlobalDefaultJdk gdj = gdj(tmp);
        gdj.set(new InstalledJdk("temurin-25.0.3", j25)); // default = 25
        gdj.setCurrent(new InstalledJdk("temurin-21.0.5", j21)); // current = 21

        var r = JdkResolution.resolve(req(tmp).build(), reg(jdks), gdj, LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.CURRENT);
        assertThat(r.jdk().get().home()).isEqualTo(j21);
    }

    @Test
    void de_facto_default_when_nothing_pinned_or_set(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        makeJdk(jdks, "temurin-17.0.13");
        makeJdk(jdks, "temurin-21.0.5");
        Path j26 = makeJdk(jdks, "temurin-26.0.1");

        // No pins, no persisted default → policy picks 26 (25 not installed).
        var r = JdkResolution.resolve(req(tmp).build(), reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.DEFAULT);
        assertThat(r.jdk().get().home()).isEqualTo(j26);
    }

    @Test
    void build_signals_install_for_an_uninstalled_pin(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        makeJdk(jdks, "temurin-21.0.5");
        var req = req(tmp).switchSpec("26").build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.jdk()).isEmpty();
        assertThat(r.wouldInstall()).isTrue();
        assertThat(r.installSpec()).isEqualTo("26");
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.SWITCH);
    }

    @Test
    void hook_falls_through_an_uninstalled_pin(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        Path j25 = makeJdk(jdks, "temurin-25.0.3");
        GlobalDefaultJdk gdj = gdj(tmp);
        gdj.set(new InstalledJdk("temurin-25.0.3", j25));
        // Switch names an uninstalled 99 — the hook must not block; it falls
        // through to the default rather than reporting wouldInstall.
        var req = req(tmp).switchSpec("99").build();

        var r = JdkResolution.resolveForHook(req, reg(jdks), gdj);
        assertThat(r.wouldInstall()).isFalse();
        assertThat(r.jdk().get().home()).isEqualTo(j25);
    }

    @Test
    void java_release_floor_kicks_in_above_latest_lts(@TempDir Path tmp) throws IOException {
        Path jdks = jdks(tmp);
        Path j26 = makeJdk(jdks, "temurin-26.0.1");
        // No jdk pin, project.java = 26 > latest LTS 25 → implied >=26.
        var req = req(tmp).projectJavaRelease(26).build();

        var r = JdkResolution.resolve(req, reg(jdks), gdj(tmp), LATEST_LTS);
        assertThat(r.tier()).isEqualTo(JdkResolution.Tier.JAVA_RELEASE_FLOOR);
        assertThat(r.jdk().get().home()).isEqualTo(j26);
    }

    // -- helpers -------------------------------------------------------------

    private static Path jdks(Path tmp) throws IOException {
        return Files.createDirectories(tmp.resolve("jdks"));
    }

    private static JdkRegistry reg(Path jdksRoot) {
        return new JdkRegistry(jdksRoot, List.of(new JkProbe(jdksRoot)));
    }

    private static GlobalDefaultJdk gdj(Path tmp) {
        Path data = tmp.resolve("data");
        return new GlobalDefaultJdk(
                data.resolve("default-jdk"), data.resolve("current-jdk"),
                data.resolve("default-graal-jdk"), tmp.resolve("config/jk.toml"));
    }

    private static Path makeJdk(Path jdksRoot, String dirName) throws IOException {
        Path home = jdksRoot.resolve(dirName);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        String version = dirName.substring(dirName.indexOf('-') + 1);
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        return home.toRealPath();
    }

    private static ReqBuilder req(Path projectDir) {
        return new ReqBuilder(projectDir);
    }

    /** Tiny builder so each test only sets the tiers it cares about. */
    private static final class ReqBuilder {
        private final Path projectDir;
        private String switchSpec, envSpec, lockJdkId, projectJdkSpec;
        private int projectJavaRelease;
        private final Map<String, String> env = new HashMap<>();

        ReqBuilder(Path projectDir) {
            this.projectDir = projectDir;
        }

        ReqBuilder switchSpec(String s) {
            this.switchSpec = s;
            return this;
        }

        ReqBuilder envSpec(String s) {
            this.envSpec = s;
            return this;
        }

        ReqBuilder lockJdkId(String s) {
            this.lockJdkId = s;
            return this;
        }

        ReqBuilder projectJdkSpec(String s) {
            this.projectJdkSpec = s;
            return this;
        }

        ReqBuilder projectJavaRelease(int r) {
            this.projectJavaRelease = r;
            return this;
        }

        JdkResolution.Request build() {
            return new JdkResolution.Request(
                    projectDir, switchSpec, envSpec, lockJdkId, projectJdkSpec, projectJavaRelease, env::get);
        }
    }
}
