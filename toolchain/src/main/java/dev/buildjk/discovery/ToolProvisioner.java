// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.discovery;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs the probe chain. Caller decides what to do with the result —
 * usually: link discovered → fall back to download → return the result.
 *
 * <p>Splitting "discover" from "link / fall back" lets the two registries
 * (JDK and build-tool) share the probe chain while keeping the
 * download-fallback logic inside their respective installers.
 */
public final class ToolProvisioner {

    private final List<LocalToolProbe> probes;

    public ToolProvisioner() { this(Probes.defaultChain()); }

    public ToolProvisioner(List<LocalToolProbe> probes) {
        this.probes = List.copyOf(Objects.requireNonNull(probes, "probes"));
    }

    /** First probe to return a match wins. Empty if nothing is found locally. */
    public Optional<DiscoveredTool> discover(ToolSpec spec) throws IOException {
        Objects.requireNonNull(spec, "spec");
        for (LocalToolProbe probe : probes) {
            Optional<DiscoveredTool> hit = probe.find(spec);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    public List<LocalToolProbe> probes() {
        return probes;
    }
}
