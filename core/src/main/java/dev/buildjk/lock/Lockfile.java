// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.lock;

import java.util.List;
import java.util.Objects;

/**
 * In-memory representation of {@code jk.lock} per PRD §9. v0.1 schema:
 * {@code version}, {@code generated-by}, {@code resolution-algorithm}, and
 * a list of resolved packages. Lockfile-checksum field comes online once
 * we ship the resolver and have content to checksum.
 */
public record Lockfile(
        int version,
        String generatedBy,
        String resolutionAlgorithm,
        List<Package> packages) {

    public static final int CURRENT_VERSION = 4;
    public static final String RESOLUTION_ALGORITHM = "pubgrub-v1";

    public Lockfile {
        Objects.requireNonNull(generatedBy, "generatedBy");
        Objects.requireNonNull(resolutionAlgorithm, "resolutionAlgorithm");
        Objects.requireNonNull(packages, "packages");
        packages = List.copyOf(packages);
    }

    public static Lockfile empty(String jkVersion) {
        return new Lockfile(CURRENT_VERSION, "jk " + jkVersion, RESOLUTION_ALGORITHM, List.of());
    }

    public record Package(
            String name,
            String version,
            String source,
            String checksum,
            String path,
            List<String> deps) {

        public Package {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(deps, "deps");
            deps = List.copyOf(deps);
        }
    }
}
