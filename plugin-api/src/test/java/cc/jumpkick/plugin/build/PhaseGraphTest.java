// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The phase DAG (default edges, closures, window validity, target resolution) and capability→phase derivation. */
class PhaseGraphTest {

    @Test
    void upstream_edges_follow_the_default_chain() {
        assertThat(PhaseGraph.upstreamOf(Phase.RESOLVE)).isEmpty();
        assertThat(PhaseGraph.upstreamOf(Phase.COMPILE)).containsExactly(Phase.RESOLVE);
        assertThat(PhaseGraph.upstreamOf(Phase.TEST)).containsExactly(Phase.COMPILE);
        assertThat(PhaseGraph.upstreamOf(Phase.PACKAGE)).containsExactly(Phase.TEST);
        assertThat(PhaseGraph.upstreamOf(Phase.IMAGE)).containsExactly(Phase.PACKAGE);
        assertThat(PhaseGraph.upstreamOf(Phase.PUBLISH)).containsExactly(Phase.PACKAGE);
    }

    @Test
    void closure_is_the_transitive_prerequisite_set() {
        assertThat(PhaseGraph.closure(Phase.PACKAGE))
                .containsExactlyInAnyOrder(Phase.RESOLVE, Phase.COMPILE, Phase.TEST, Phase.PACKAGE);
        assertThat(PhaseGraph.closure(Phase.RESOLVE)).containsExactly(Phase.RESOLVE);
    }

    @Test
    void precedes_and_window_validity() {
        assertThat(PhaseGraph.precedes(Phase.RESOLVE, Phase.PACKAGE)).isTrue();
        assertThat(PhaseGraph.precedes(Phase.PACKAGE, Phase.COMPILE)).isFalse();
        assertThat(PhaseGraph.precedes(Phase.COMPILE, Phase.COMPILE)).isFalse();

        assertThat(PhaseGraph.isValidWindow(Phase.RESOLVE, Phase.COMPILE)).isTrue(); // protobuf
        assertThat(PhaseGraph.isValidWindow(Phase.COMPILE, Phase.PACKAGE)).isTrue(); // spring-aot
        assertThat(PhaseGraph.isValidWindow(Phase.RESOLVE, Phase.TEST)).isTrue(); // android test-config
        assertThat(PhaseGraph.isValidWindow(Phase.COMPILE, Phase.COMPILE)).isTrue(); // same phase is fine
        assertThat(PhaseGraph.isValidWindow(Phase.PACKAGE, Phase.COMPILE)).isFalse(); // reversed
    }

    @Test
    void target_resolution_drops_the_test_gate_when_skipped() {
        assertThat(PhaseGraph.closureFor(Phase.IMAGE, true))
                .containsExactlyInAnyOrder(Phase.RESOLVE, Phase.COMPILE, Phase.TEST, Phase.PACKAGE, Phase.IMAGE);
        assertThat(PhaseGraph.closureFor(Phase.IMAGE, false))
                .containsExactlyInAnyOrder(Phase.RESOLVE, Phase.COMPILE, Phase.PACKAGE, Phase.IMAGE);
        // A test target keeps TEST even when the gate is nominally "off".
        assertThat(PhaseGraph.closureFor(Phase.TEST, false)).contains(Phase.TEST);
    }

    @Test
    void capabilities_derive_phases_from_implemented_interfaces() {
        Object buildAndPackage = new CapabilityHarnessTest.FixturePlugin();
        assertThat(Capabilities.phasesOf(buildAndPackage)).containsExactlyInAnyOrder(Phase.COMPILE, Phase.PACKAGE);
        assertThat(Capabilities.phasesOf(new Object())).isEmpty();
    }

    @Test
    void plugin_phases_are_derived_from_capabilities() {
        Plugin buildOnly = new Plugin() {
            @Override
            public PluginManifest manifest() {
                return new PluginManifest("jk-x", "##X:");
            }

            @Override
            public int run(List<String> args, ProtocolWriter out) {
                return 0;
            }
        };
        assertThat(buildOnly.phases()).isEmpty(); // a bare Plugin implements no capability

        Plugin capable = new CapabilityHarnessTest.FixturePlugin();
        assertThat(capable.phases()).containsExactlyInAnyOrder(Phase.COMPILE, Phase.PACKAGE);
        assertThat((Set<Phase>) capable.phases()).isEqualTo(Capabilities.phasesOf(capable));
    }
}
