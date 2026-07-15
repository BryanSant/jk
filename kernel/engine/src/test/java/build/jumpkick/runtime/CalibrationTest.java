// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import build.jumpkick.model.JkVersion;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Host calibration: the probe-derived anchor, TOML round-trip, staleness, and refine fold. */
class CalibrationTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void deriveMsPerWeight_anchors_measured_time_to_the_synthetic_weight() {
        // weight = TEST_STARTUP(15) + COMPILE_FLOOR(2) + compileWeight(5)=1 = 18; (fork+javac)/18.
        assertThat(Calibration.deriveMsPerWeight(180, 180)).isCloseTo(20.0, within(1e-6));
    }

    @Test
    void round_trips_through_toml(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("calibration.toml");
        Calibration written = Calibration.testInstance(42.5, true, JkVersion.VERSION, NOW);
        Calibration.writeTo(f, written);
        Calibration read = Calibration.readFrom(f, NOW);
        assertThat(read.present()).isTrue();
        assertThat(read.measured()).isTrue();
        assertThat(read.msPerWeight()).isCloseTo(42.5, within(1e-3));
    }

    @Test
    void missing_file_is_absent_and_falls_back_to_the_constant() {
        Calibration absent = Calibration.readFrom(Path.of("/no/such/calibration.toml"), NOW);
        assertThat(absent.present()).isFalse();
        assertThat(absent.msPerWeight()).isEqualTo((double) EffortWeights.MS_PER_WEIGHT);
    }

    @Test
    void a_different_jk_version_is_stale() {
        assertThat(Calibration.stale("0.0.0-OLD", NOW, NOW)).isTrue();
        assertThat(Calibration.stale(JkVersion.VERSION, NOW, NOW)).isFalse();
    }

    @Test
    void an_aged_file_is_stale() {
        long updated = NOW - 61 * DAY; // TTL is ~60 days
        assertThat(Calibration.stale(JkVersion.VERSION, updated, NOW)).isTrue();
        assertThat(Calibration.stale(JkVersion.VERSION, NOW - 30 * DAY, NOW)).isFalse();
    }

    @Test
    void stale_file_reads_as_absent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("calibration.toml");
        Calibration.writeTo(f, Calibration.testInstance(42.5, true, "0.0.0-OLD", NOW));
        assertThat(Calibration.readFrom(f, NOW).present()).isFalse();
    }

    @Test
    void refine_replaces_a_probe_bootstrap_on_the_first_real_measurement() {
        Calibration probe = Calibration.testInstance(19.0, false, JkVersion.VERSION, NOW); // measured=false
        Calibration refined = Calibration.foldRefine(probe, 158.0, NOW + 1);
        assertThat(refined.measured()).isTrue();
        assertThat(refined.msPerWeight()).isCloseTo(158.0, within(1e-6)); // replaced, not EWMA
    }

    @Test
    void refine_ewma_smooths_once_measured() {
        Calibration measured = Calibration.testInstance(100.0, true, JkVersion.VERSION, NOW);
        Calibration refined = Calibration.foldRefine(measured, 200.0, NOW + 1);
        assertThat(refined.msPerWeight()).isCloseTo(140.0, within(1e-6)); // 0.4*200 + 0.6*100
    }
}
