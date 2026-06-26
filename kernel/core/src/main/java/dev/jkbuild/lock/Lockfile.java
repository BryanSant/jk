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
 * <p>The schema is pinned to {@code version = 1} for the pre-1.x window; the version bumps when we
 * cut a public release. Fields added in this window stay backward-compatible at the reader (unknown
 * / missing optional fields fall back to defaults).
 *
 * <p>{@code jdk} is the resolved JDK install identifier the project was built against (e.g. {@code
 * temurin-25.0.3}), stamped by {@code jk new} and refreshed by {@code jk lock}. Optional; {@code
 * null} for legacy lockfiles produced before the field existed.
 *
 * <p>{@code kotlin} is the resolved Kotlin compiler version (e.g. {@code 2.3.21}) that the floating
 * {@code project.kotlin} selector locked to, stamped by {@code jk lock}. Optional; {@code null} for
 * Java projects or when resolution was skipped (offline).
 */
public record Lockfile(
        int version,
        String generatedBy,
        String resolutionAlgorithm,
        String jdk,
        String kotlin,
        List<Artifact> artifacts,
        List<PluginEntry> plugins) {

    public static final int CURRENT_VERSION = 1;
    public static final int MIN_SUPPORTED_VERSION = 1;
    public static final String RESOLUTION_ALGORITHM = "pubgrub-v1";

    public Lockfile {
        Objects.requireNonNull(generatedBy, "generatedBy");
        Objects.requireNonNull(resolutionAlgorithm, "resolutionAlgorithm");
        Objects.requireNonNull(artifacts, "artifacts");
        artifacts = List.copyOf(artifacts);
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
    }

    /** Back-compat constructor without plugin entries. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            String jdk,
            String kotlin,
            List<Artifact> artifacts) {
        this(version, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, List.of());
    }

    /** Back-compat constructor for callers that stamp a JDK but no Kotlin version. */
    public Lockfile(int version, String generatedBy, String resolutionAlgorithm, String jdk, List<Artifact> artifacts) {
        this(version, generatedBy, resolutionAlgorithm, jdk, null, artifacts, List.of());
    }

    /** Back-compat constructor for callers that don't yet stamp a JDK. */
    public Lockfile(int version, String generatedBy, String resolutionAlgorithm, List<Artifact> artifacts) {
        this(version, generatedBy, resolutionAlgorithm, null, null, artifacts, List.of());
    }

    /** Return a copy with the resolved Kotlin compiler version stamped in. */
    public Lockfile withKotlin(String kotlinVersion) {
        return new Lockfile(version, generatedBy, resolutionAlgorithm, jdk, kotlinVersion, artifacts, plugins);
    }

    /** Return a copy with the given plugin entries (replaces any existing). */
    public Lockfile withPlugins(List<PluginEntry> newPlugins) {
        return new Lockfile(version, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, newPlugins);
    }

    public static Lockfile empty(String jkVersion) {
        return empty(jkVersion, null);
    }

    /** Empty artifact set with a resolved JDK pinned for the project. */
    public static Lockfile empty(String jkVersion, String jdk) {
        return new Lockfile(CURRENT_VERSION, "jk " + jkVersion, RESOLUTION_ALGORITHM, jdk, null, List.of(), List.of());
    }

    /**
     * A third-party plugin pinned in {@code jk.lock}.
     *
     * @param coordinate Maven {@code group:name} (e.g. {@code "com.example:my-jk-plugin"})
     * @param version exact version (e.g. {@code "1.2.0"})
     * @param checksum {@code sha256:<hex>} content hash of the plugin JAR
     */
    public record PluginEntry(String coordinate, String version, String checksum) {
        public PluginEntry {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(checksum, "checksum");
        }

        /** The raw hex SHA-256 (strips the {@code "sha256:"} prefix if present). */
        public String sha256Hex() {
            return checksum.startsWith("sha256:") ? checksum.substring(7) : checksum;
        }
    }

    public record Artifact(
            String name,
            String version,
            String source,
            String checksum,
            String path,
            List<Scope> scopes,
            List<String> deps,
            String pinnedBy,
            GitInfo git,
            /** SHA-256 of the {@code -sources.jar}, or {@code null} when not published. */
            String sourcesChecksum) {

        public Artifact {
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

        /** Without sources checksum (the common case). */
        public Artifact(
                String name,
                String version,
                String source,
                String checksum,
                String path,
                List<Scope> scopes,
                List<String> deps,
                String pinnedBy,
                GitInfo git) {
            this(name, version, source, checksum, path, scopes, deps, pinnedBy, git, null);
        }

        /** Without git provenance — the common Maven-coordinate case. */
        public Artifact(
                String name,
                String version,
                String source,
                String checksum,
                String path,
                List<Scope> scopes,
                List<String> deps,
                String pinnedBy) {
            this(name, version, source, checksum, path, scopes, deps, pinnedBy, null, null);
        }

        /**
         * Back-compat constructor without {@code pinnedBy} — for callers that don't track BOM
         * provenance. Equivalent to passing {@code null}.
         */
        public Artifact(
                String name,
                String version,
                String source,
                String checksum,
                String path,
                List<Scope> scopes,
                List<String> deps) {
            this(name, version, source, checksum, path, scopes, deps, null, null, null);
        }

        /** Convenience constructor for callers that don't care about scopes (defaults to MAIN). */
        public Artifact(String name, String version, String source, String checksum, String path, List<String> deps) {
            this(name, version, source, checksum, path, List.of(Scope.MAIN), deps, null, null);
        }

        public boolean inAnyScope(Set<Scope> include) {
            for (Scope s : scopes) if (include.contains(s)) return true;
            return false;
        }

        /**
         * Provenance for a git-source artifact: the canonical repo URL, the resolved commit SHA, and
         * the original ref token (e.g. {@code tag:v1}). Present only for git-built artifacts; null for
         * Maven coordinates.
         */
        public record GitInfo(String url, String rev, String ref) {
            public GitInfo {
                Objects.requireNonNull(url, "url");
                Objects.requireNonNull(rev, "rev");
            }
        }
    }
}
