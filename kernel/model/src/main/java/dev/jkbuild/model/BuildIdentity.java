// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * The running jk's identity for cache keys and engine election.
 *
 * <p>Release versions are immutable, so {@link JkVersion#VERSION} alone identifies them. A
 * {@code -SNAPSHOT} dev build is a MOVING TARGET under a constant version string — trusting the
 * string produced two real bugs: action-cache restores serving an older engine's bytes, and
 * stale engines winning same-version elections against fresh spawns. For snapshots, identity is
 * therefore content-based: the sha256 (first 12 hex) of the code archive this class loaded from.
 *
 * <p>{@link #buildId()} is {@code ""} when no jar identity is derivable (unit tests running from
 * a classes directory, the native client image) — callers treat empty as "no opinion" and fall
 * back to the version-string rule, which preserves release behavior exactly.
 */
public final class BuildIdentity {

    private static volatile String cachedBuildId;

    private BuildIdentity() {}

    /** Content identity of the running code archive, or {@code ""} when none is derivable. */
    public static String buildId() {
        String local = cachedBuildId;
        if (local != null) return local;
        synchronized (BuildIdentity.class) {
            if (cachedBuildId == null) cachedBuildId = computeBuildId();
            return cachedBuildId;
        }
    }

    /**
     * The version string to key caches by: bare {@link JkVersion#VERSION} for releases; for
     * {@code -SNAPSHOT} builds the build id is folded in so a rebuilt engine never restores an
     * older engine's results under the same key.
     */
    public static String cacheKeyVersion() {
        return compose(JkVersion.VERSION, buildId());
    }

    /** Pure composition rule, separated for tests. */
    static String compose(String version, String id) {
        return version.endsWith("-SNAPSHOT") && !id.isEmpty() ? version + "+" + id : version;
    }

    private static String computeBuildId() {
        try {
            var source = BuildIdentity.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return "";
            Path location = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(location) || !location.toString().endsWith(".jar")) {
                return ""; // classes dir (tests) or a native image — no jar identity
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(location)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder(12);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                if (hex.length() >= 12) break;
            }
            return hex.substring(0, 12);
        } catch (Exception e) {
            return ""; // identity is best-effort; the version-string rule still applies
        }
    }
}
