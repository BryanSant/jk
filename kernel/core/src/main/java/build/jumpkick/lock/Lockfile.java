// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.lock;

import build.jumpkick.model.Coordinate;
import build.jumpkick.model.Scope;
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
        List<PluginEntry> plugins,
        List<SdkEntry> sdk,
        JkToolchain jk) {

    /**
     * The jk toolchain that resolved this lock (engine-versioning-plan §3): the exact version a
     * checkout builds with (the project wrapper's contract — one grep-able line) and, for
     * release versions, the engine jar's sha256 so every later fetch on any machine verifies
     * against the pin, not just the release site's checksums. {@code sha256} is empty for
     * -SNAPSHOT dev builds (never published).
     */
    public record JkToolchain(String version, String sha256) {}

    public static final int CURRENT_VERSION = 1;
    public static final int MIN_SUPPORTED_VERSION = 1;
    public static final String RESOLUTION_ALGORITHM = "pubgrub-v1";

    public Lockfile {
        Objects.requireNonNull(generatedBy, "generatedBy");
        Objects.requireNonNull(resolutionAlgorithm, "resolutionAlgorithm");
        Objects.requireNonNull(artifacts, "artifacts");
        artifacts = List.copyOf(artifacts);
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        sdk = sdk == null ? List.of() : List.copyOf(sdk);
    }

    /** Back-compat constructor without the jk toolchain pin. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            String jdk,
            String kotlin,
            List<Artifact> artifacts,
            List<PluginEntry> plugins,
            List<SdkEntry> sdk) {
        this(version, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, plugins, sdk, null);
    }

    /** This lock with the jk toolchain pin set. */
    public Lockfile withJk(JkToolchain toolchain) {
        return new Lockfile(
                version, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, plugins, sdk, toolchain);
    }

    /** Back-compat constructor without SDK entries. */
    public Lockfile(
            int version,
            String generatedBy,
            String resolutionAlgorithm,
            String jdk,
            String kotlin,
            List<Artifact> artifacts,
            List<PluginEntry> plugins) {
        this(version, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, plugins, List.of());
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
        return new Lockfile(version, generatedBy, resolutionAlgorithm, jdk, kotlinVersion, artifacts, plugins, sdk);
    }

    /** Return a copy with the given plugin entries (replaces any existing). */
    public Lockfile withPlugins(List<PluginEntry> newPlugins) {
        return new Lockfile(version, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, newPlugins, sdk);
    }

    /** Return a copy with the given provisioned-SDK component pins (replaces any existing). */
    public Lockfile withSdk(List<SdkEntry> newSdk) {
        return new Lockfile(version, generatedBy, resolutionAlgorithm, jdk, kotlin, artifacts, plugins, newSdk);
    }

    public static Lockfile empty(String jkVersion) {
        return empty(jkVersion, null);
    }

    /** Empty artifact set with a resolved JDK pinned for the project. */
    public static Lockfile empty(String jkVersion, String jdk) {
        return new Lockfile(
                CURRENT_VERSION, "jk " + jkVersion, RESOLUTION_ALGORITHM, jdk, null, List.of(), List.of(), List.of());
    }

    /**
     * A third-party plugin pinned in {@code jk.lock}.
     *
     * @param coordinate Maven {@code group:name} (e.g. {@code "com.example:my-jk-plugin"})
     * @param version exact version (e.g. {@code "1.2.0"})
     * @param checksum {@code sha256:<hex>} content hash of the plugin JAR
     */
    /**
     * A provisioned-SDK component pinned in {@code jk.lock} (android-plan §3.2 — hermetic,
     * lockfile-pinned SDK components): the sdkmanager-style path and the dotted revision the
     * build provisioned/observed. Written only for projects whose plugins contribute
     * {@code sdk-component} step-dependencies.
     */
    public record SdkEntry(String component, String revision) {
        public SdkEntry {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(revision, "revision");
        }
    }

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

        /** The {@code group} segment of {@link #name} ({@code group:artifact}). */
        public String moduleGroup() {
            int c = name.indexOf(':');
            return c < 0 ? name : name.substring(0, c);
        }

        /** The {@code artifact} segment of {@link #name}. */
        public String moduleArtifact() {
            int c = name.indexOf(':');
            return c < 0 ? "" : name.substring(c + 1);
        }

        /** This artifact as a jar {@link Coordinate} at its {@link #version} — the single currency. */
        public Coordinate coordinate() {
            // The optional `path` field carries the artifact's real file name when the packaging
            // is not a plain jar (an androidx AAR) — the coordinate's type follows it, so every
            // fetch/locate path (sync, repo store, IDE fetch) asks for the right extension.
            if (isAar()) {
                return new Coordinate(moduleGroup(), moduleArtifact(), version, null, "aar");
            }
            return Coordinate.of(moduleGroup(), moduleArtifact(), version);
        }

        /** True when the locked artifact is an Android AAR (its {@code path} names one). */
        public boolean isAar() {
            return path != null && path.endsWith(".aar");
        }

        /** Raw hex SHA-256 of the jar (strips a {@code "sha256:"} prefix), or {@code null}. */
        public String checksumHex() {
            return stripSha256(checksum);
        }

        /** Raw hex SHA-256 of the {@code -sources.jar} (strips the prefix), or {@code null}. */
        public String sourcesChecksumHex() {
            return stripSha256(sourcesChecksum);
        }

        private static String stripSha256(String c) {
            if (c == null) return null;
            return c.startsWith("sha256:") ? c.substring(7) : c;
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
