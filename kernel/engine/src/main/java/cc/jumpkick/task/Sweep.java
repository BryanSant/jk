// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.util.Hashing;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Constants + small helpers shared by the future mark-and-sweep prune.
 *
 * <p>Lives in {@code engine.task} alongside {@link ActionCache} and {@link SyncManifest} because
 * they're the writers of the roots the sweep walks. The sweep itself lands in a later commit; for
 * now this class holds the contract everyone has to agree on.
 */
public final class Sweep {

    /**
     * How long a freshly-written CAS object is unconditionally protected from the sweep, even if no
     * surviving root references it.
     *
     * <p>Closes the window between "writer A puts an object into the CAS" and "writer B writes the
     * root that references it" — most relevantly a {@code jk sync} that fetches deps but has no
     * associated build yet, or any future CAS writer that hasn't been taught to root its outputs. One
     * hour is generous enough to cover an interactive workflow but short enough that genuine garbage
     * doesn't survive long.
     */
    public static final Duration MIN_AGE_FOR_SWEEP = Duration.ofHours(1);

    /**
     * Subdirectory under the action-cache root where {@code jk sync} stamps per-project reachability
     * manifests.
     */
    public static final String SYNCED_SUBDIR = "synced";

    private Sweep() {}

    /**
     * Stable per-project identifier derived from the lockfile's absolute path. Moving the project on
     * disk (or pointing at a different lockfile) yields a different fingerprint — the old manifest
     * ages out via the regular TTL, the new one starts fresh.
     */
    public static String projectFingerprint(Path lockFile) {
        String key = lockFile.toAbsolutePath().normalize().toString();
        return Hashing.sha256Hex(key);
    }
}
