// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JkDiffTest {

    @Test
    void empty_diff_encodes_to_empty_string() {
        assertThat(JkDiff.empty().encode()).isEmpty();
    }

    @Test
    void round_trips_basic_keys() {
        var src = new LinkedHashMap<String, String>();
        src.put("JAVA_HOME", "/opt/jdk-21");
        src.put("PATH", "/usr/local/bin:/usr/bin");
        var encoded = new JkDiff(src).encode();

        var decoded = JkDiff.parse(encoded);
        assertThat(decoded.previousValue("JAVA_HOME")).isEqualTo("/opt/jdk-21");
        assertThat(decoded.previousValue("PATH")).isEqualTo("/usr/local/bin:/usr/bin");
    }

    @Test
    void unset_sentinel_round_trips() {
        var src = Map.of("JAVA_HOME", JkDiff.UNSET_SENTINEL);
        var decoded = JkDiff.parse(new JkDiff(src).encode());
        assertThat(decoded.wasUnset("JAVA_HOME")).isTrue();
        assertThat(decoded.wasUnset("PATH")).isFalse();
    }

    @Test
    void malformed_payload_yields_empty_diff() {
        assertThat(JkDiff.parse("not-base64").keys()).isEmpty();
        assertThat(JkDiff.parse("").keys()).isEmpty();
        assertThat(JkDiff.parse(null).keys()).isEmpty();
    }

    @Test
    void values_with_newlines_or_nulls_serialize_safely() {
        // Real-world values shouldn't contain \0, but we should at least
        // tolerate values containing colons and slashes which our format
        // uses internally for delimiting.
        var src = Map.of("VAR", "a:b/c.d");
        var decoded = JkDiff.parse(new JkDiff(src).encode());
        assertThat(decoded.previousValue("VAR")).isEqualTo("a:b/c.d");
    }

    @Test
    void next_captures_pre_jk_values_from_snapshot() {
        // No prior diff. Target adds JAVA_HOME + PATH. Snapshot has both with
        // pre-existing values — those should be recorded as the "previous".
        var prior = JkDiff.empty();
        var target = new JkEnv.Target(
                Optional.of(Path.of("/project")),
                Map.of(
                        "JAVA_HOME", "/opt/jdk-25",
                        "PATH", "/opt/jdk-25/bin:/usr/bin"));
        var snapshot = (JkDiff.EnvSnapshot) k -> switch (k) {
            case "JAVA_HOME" -> "/usr/lib/jvm/system";
            case "PATH" -> "/usr/bin";
            default -> null;
        };
        var next = prior.next(target, snapshot);
        assertThat(next.previousValue("JAVA_HOME")).isEqualTo("/usr/lib/jvm/system");
        assertThat(next.previousValue("PATH")).isEqualTo("/usr/bin");
        assertThat(next.wasUnset("JAVA_HOME")).isFalse();
    }

    @Test
    void next_preserves_prior_pre_jk_value_across_re_activation() {
        // The user `cd`s into one project, then another. The diff carries
        // the original pre-activation JAVA_HOME — not the previous project's
        // JAVA_HOME — so deactivating later restores correctly.
        var prior = new JkDiff(Map.of("JAVA_HOME", "/usr/lib/jvm/system"));
        var target = new JkEnv.Target(Optional.of(Path.of("/another-project")), Map.of("JAVA_HOME", "/opt/jdk-25"));
        var snapshot = (JkDiff.EnvSnapshot) k -> "/opt/jdk-21"; // not what we want — would shadow
        var next = prior.next(target, snapshot);
        assertThat(next.previousValue("JAVA_HOME")).isEqualTo("/usr/lib/jvm/system");
    }

    @Test
    void next_records_unset_sentinel_for_keys_not_in_environment() {
        var prior = JkDiff.empty();
        var target = new JkEnv.Target(Optional.of(Path.of("/project")), Map.of("GRAALVM_HOME", "/opt/graalvm"));
        var snapshot = (JkDiff.EnvSnapshot) k -> null; // nothing set in env
        var next = prior.next(target, snapshot);
        assertThat(next.wasUnset("GRAALVM_HOME")).isTrue();
    }
}
