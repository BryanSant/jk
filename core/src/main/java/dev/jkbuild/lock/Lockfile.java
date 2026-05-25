// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.lock;

import dev.jkbuild.model.Scope;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory representation of {@code jk.lock} per PRD §9.
 *
 * <p>The schema is pinned to {@code version = 1} for the pre-1.x window;
 * the version bumps when we cut a public release. Fields added in this
 * window stay backward-compatible at the reader (unknown / missing optional
 * fields fall back to defaults).
 *
 * <p>{@code jdk} is the resolved JDK install identifier the project was
 * built against (e.g. {@code temurin-25.0.3}), stamped by {@code jk new}
 * and refreshed by {@code jk lock}. Optional; {@code null} for legacy
 * lockfiles produced before the field existed.
 */
public record Lockfile(
        int version,
        String generatedBy,
        String resolutionAlgorithm,
        String jdk,
        List<Package> packages) {

    public static final int CURRENT_VERSION = 1;
    public static final int MIN_SUPPORTED_VERSION = 1;
    public static final String RESOLUTION_ALGORITHM = "pubgrub-v1";

    public Lockfile {
        Objects.requireNonNull(generatedBy, "generatedBy");
        Objects.requireNonNull(resolutionAlgorithm, "resolutionAlgorithm");
        Objects.requireNonNull(packages, "packages");
        packages = List.copyOf(packages);
    }

    /** Back-compat constructor for callers that don't yet stamp a JDK. */
    public Lockfile(int version, String generatedBy, String resolutionAlgorithm, List<Package> packages) {
        this(version, generatedBy, resolutionAlgorithm, null, packages);
    }

    public static Lockfile empty(String jkVersion) {
        return empty(jkVersion, null);
    }

    /** Empty package set with a resolved JDK pinned for the project. */
    public static Lockfile empty(String jkVersion, String jdk) {
        return new Lockfile(CURRENT_VERSION, "jk " + jkVersion, RESOLUTION_ALGORITHM, jdk, List.of());
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
