// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The compile-weight formula: ceil(sources × 0.1), floored at 1 when the phase runs. */
class EffortWeightsTest {

    @Test
    void compile_weight_is_ceil_of_a_tenth() {
        assertThat(EffortWeights.compileWeight(200)).isEqualTo(20);   // 200 → 20 (your example)
        assertThat(EffortWeights.compileWeight(201)).isEqualTo(21);   // rounds up
        assertThat(EffortWeights.compileWeight(11)).isEqualTo(2);
        assertThat(EffortWeights.compileWeight(10)).isEqualTo(1);
        assertThat(EffortWeights.compileWeight(1)).isEqualTo(1);      // floored at 1
        assertThat(EffortWeights.compileWeight(0)).isEqualTo(1);      // floored at 1
    }

    @Test
    void unit_weights_match_the_spec() {
        assertThat(EffortWeights.TEST_METHOD).isEqualTo(8);
        assertThat(EffortWeights.ARTIFACT_FETCH).isEqualTo(8);
        assertThat(EffortWeights.PACKAGE_JAR).isEqualTo(5);
        assertThat(EffortWeights.SKIP).isEqualTo(1);
    }
}
