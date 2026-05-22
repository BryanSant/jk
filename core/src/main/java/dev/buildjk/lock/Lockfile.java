// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.lock;

import dev.buildjk.model.Scope;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory representation of {@code jk.lock} per PRD §9.
 *
 * <p>v0.3 schema (v5): each package carries the list of scopes whose
 * resolution reached it. v0.2-era v4 lockfiles still parse — every
 * package is treated as {@code [main]}.
 */
public record Lockfile(
        int version,
        String generatedBy,
        String resolutionAlgorithm,
        List<Package> packages) {

    public static final int CURRENT_VERSION = 5;
    public static final int MIN_SUPPORTED_VERSION = 4;
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
            List<Scope> scopes,
            List<String> deps) {

        public Package {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(deps, "deps");
            Objects.requireNonNull(scopes, "scopes");
            // Canonicalize scope order for stable lockfile output.
            EnumSet<Scope> set = EnumSet.noneOf(Scope.class);
            set.addAll(scopes);
            scopes = new ArrayList<>(set);
            deps = List.copyOf(deps);
        }

        /** Convenience constructor for callers that don't care about scopes (defaults to MAIN). */
        public Package(
                String name, String version, String source,
                String checksum, String path, List<String> deps) {
            this(name, version, source, checksum, path, List.of(Scope.MAIN), deps);
        }

        public boolean inAnyScope(Set<Scope> include) {
            for (Scope s : scopes) if (include.contains(s)) return true;
            return false;
        }
    }
}
