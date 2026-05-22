// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.publish;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlsaProvenanceTest {

    @Test
    void renders_a_well_formed_intoto_statement() {
        var subject = new SlsaProvenance.Subject("widget-1.0.0.jar", "deadbeef");
        var ctx = new SlsaProvenance.BuildContext(
                "https://github.com/buildjk/jk",
                "https://buildjk.dev/jk-build/v1",
                "00000000-0000-0000-0000-000000000000",
                Instant.parse("2026-05-22T12:00:00Z"),
                Instant.parse("2026-05-22T12:00:01Z"),
                Map.of("configRef", "build.jk"),
                Map.of("jdk", "21"));

        String json = new String(SlsaProvenance.generate(List.of(subject), ctx), StandardCharsets.UTF_8);

        assertThat(json).contains("\"_type\":\"https://in-toto.io/Statement/v1\"");
        assertThat(json).contains("\"predicateType\":\"https://slsa.dev/provenance/v1\"");
        assertThat(json).contains("\"name\":\"widget-1.0.0.jar\"");
        assertThat(json).contains("\"sha256\":\"deadbeef\"");
        assertThat(json).contains("\"buildType\":\"https://buildjk.dev/jk-build/v1\"");
        assertThat(json).contains("\"id\":\"https://github.com/buildjk/jk\"");
        assertThat(json).contains("\"invocationId\":\"00000000-0000-0000-0000-000000000000\"");
        assertThat(json).contains("\"startedOn\":\"2026-05-22T12:00:00Z\"");
        assertThat(json).contains("\"configRef\":\"build.jk\"");
        assertThat(json).contains("\"jdk\":\"21\"");
    }

    @Test
    void escapes_special_characters_in_values() {
        var subject = new SlsaProvenance.Subject("a\"b\\c.jar", "0");
        var ctx = new SlsaProvenance.BuildContext(
                "x", "y", null,
                Instant.EPOCH, Instant.EPOCH, Map.of(), Map.of());
        String json = new String(SlsaProvenance.generate(List.of(subject), ctx), StandardCharsets.UTF_8);
        assertThat(json).contains("\"name\":\"a\\\"b\\\\c.jar\"");
    }

    @Test
    void rejects_empty_subject_list() {
        var ctx = new SlsaProvenance.BuildContext(
                "x", "y", "i", Instant.EPOCH, Instant.EPOCH, Map.of(), Map.of());
        assertThatThrownBy(() -> SlsaProvenance.generate(List.of(), ctx))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
