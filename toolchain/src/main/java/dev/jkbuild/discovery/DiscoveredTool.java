// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A successful probe hit: the absolute install root, the version we read
 * off disk (proves the match), and the probe name for diagnostics.
 *
 * <p>{@code home} is the resolved real path. Probes resolve through
 * {@code current} symlinks (SDKMAN) and {@code opt -> Cellar} indirection
 * (Homebrew) so the value stored here is stable across user actions on
 * the upstream installer.
 */
public record DiscoveredTool(Path home, String detectedVersion, String source) {

    public DiscoveredTool {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(detectedVersion, "detectedVersion");
        Objects.requireNonNull(source, "source");
    }
}
