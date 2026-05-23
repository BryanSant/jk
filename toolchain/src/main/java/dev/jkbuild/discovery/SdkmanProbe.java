// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code ~/.sdkman/candidates/<kind>/<version>/} (and, for JDKs, also
 * {@code ~/.sdkman/candidates/java/<version>-<dist>/} per SDKMAN's
 * identifier scheme).
 *
 * <p>SDKMAN's {@code current} symlink is intentionally NOT followed —
 * we resolve to the real version dir so the link from jk stays stable
 * even when the user {@code sdk default}s a different version.
 */
public final class SdkmanProbe implements LocalToolProbe {

    private final Path sdkmanRoot;

    public SdkmanProbe() {
        this(Path.of(System.getProperty("user.home"), ".sdkman"));
    }

    SdkmanProbe(Path sdkmanRoot) {
        this.sdkmanRoot = sdkmanRoot;
    }

    @Override
    public String name() { return "sdkman"; }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        Path candidates = sdkmanRoot.resolve("candidates").resolve(spec.kind());
        if (!Files.isDirectory(candidates)) return Optional.empty();

        // For JDKs, SDKMAN identifiers are <version>-<dist> (e.g. 21.0.5-tem).
        // Build the candidate path accordingly; for build tools the version
        // alone is the directory name.
        Path candidate;
        if ("java".equals(spec.kind()) && spec.distribution() != null) {
            candidate = candidates.resolve(spec.version() + "-" + spec.distribution());
        } else {
            candidate = candidates.resolve(spec.version());
        }
        if (!Files.isDirectory(candidate)) return Optional.empty();

        Path resolved = candidate.toRealPath();
        if (!ToolHealth.isHealthy(spec, resolved)) return Optional.empty();
        return Optional.of(new DiscoveredTool(resolved, spec.version(), name()));
    }
}
