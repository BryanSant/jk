// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.JdkHit;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Service-provider interface for "find me a copy of this tool that's
 * already installed locally" (the JBang neighbour pattern).
 *
 * <p>Implementations are stateless and cheap — discovery runs at the
 * front of every {@code jk mvn} / {@code jk build} invocation when the
 * tool isn't already cached under the jk cache directory. Target latency
 * for the whole chain: &lt; 50 ms.
 *
 * <p>The default chain is hard-wired in {@link Probes#defaultChain()};
 * additional probes can be registered via
 * {@link java.util.ServiceLoader}. On the native-image binary
 * ServiceLoader can only see probes baked in at build time — runtime
 * plugin discovery is JVM-mode only, by design.
 */
public interface LocalToolProbe {

    /** Probe identifier, surfaced in diagnostics and {@code jk jdk list}. */
    String name();

    /** Return a matching install, or empty if this probe doesn't have one. */
    Optional<DiscoveredTool> find(ToolSpec spec) throws IOException;

    /**
     * Enumerate every JDK install this probe knows about. Implementations
     * must fail fast: if the probe's root location isn't present on the
     * host, return an empty list immediately without filesystem walks or
     * subprocess calls. The default implementation returns empty, so probes
     * opt in by overriding.
     *
     * <p>Used by {@code jk jdk discover} to surface JDKs installed outside
     * jk's managed directory.
     */
    default List<JdkHit> discoverAllJdks() throws IOException {
        return List.of();
    }
}
