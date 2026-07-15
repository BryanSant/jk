// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.jdk;

import java.nio.file.Path;

/**
 * A JDK install found by a {@link build.jumpkick.discovery.LocalToolProbe}. Carries the canonical home
 * path, the version (read from the {@code release} file), the resolved vendor in {@code jk}'s
 * normalised vocabulary, and the probe name that surfaced it.
 *
 * <p>Single parse of the {@code release} file feeds all three of {@code version}, {@code vendor},
 * and (transitively) the cross-scheme identifiers — see {@link
 * build.jumpkick.discovery.ProbeSupport#discoverJdk}.
 */
public record JdkHit(Path home, String version, JdkVendor vendor, String source) {}
