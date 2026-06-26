// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.lock.Lockfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-project reachability manifest written by {@code jk sync}. Lives
 * under {@code actions/synced/<projectFingerprint>} so a sweep can treat
 * each project's synced deps as a root.
 *
 * <p>Why this matters: between {@code jk sync} and the project's first
 * {@code jk build}, dep jars sit in the CAS with no action record naming
 * them. A naive mark-and-sweep would collect them as garbage. The sync
 * manifest closes that gap — sync stamps "these shas belong to this
 * project's locked deps" and the sweep treats them as live as long as
 * the manifest exists. The manifest itself ages out via the standard
 * action-record TTL, so stale projects' deps eventually become
 * collectible.
 *
 * <p>On-disk format (plain text, one directive per line):
 * <pre>{@code
 *   PROJECT  <fingerprint>          # matches the filename
 *   LOCKFILE <absolute-path>        # debug aid; not load-bearing
 *   STAMP    <epoch-millis>         # when the manifest was written
 *   REF      <sha256>               # one per locked dep with a checksum
 * }</pre>
 *
 * <p>{@code REF} entries are deduplicated and sorted on write so two
 * runs of {@code jk sync} against the same lockfile produce byte-
 * identical manifests (useful for CI diffs, and incidentally cheap to
 * deduplicate in memory).
 */
public final class SyncManifest {

    /** Filename suffix relative to {@code <cacheRoot>/actions/}. */
    private SyncManifest() {}

    /**
     * Write a manifest for {@code lock} into the standard location.
     * {@code actionRoot} is the parent of {@code keys/} —
     * {@link Sweep#SYNCED_SUBDIR} sits beside it.
     */
    public static Path write(Path actionRoot, Path lockFile, Lockfile lock) throws IOException {
        Path syncedDir = actionRoot.resolve(Sweep.SYNCED_SUBDIR);
        Files.createDirectories(syncedDir);
        String fingerprint = Sweep.projectFingerprint(lockFile);
        Path target = syncedDir.resolve(fingerprint);

        Set<String> refs = collectRefs(lock);
        StringBuilder sb = new StringBuilder();
        sb.append("PROJECT ").append(fingerprint).append('\n');
        sb.append("LOCKFILE ").append(lockFile.toAbsolutePath().normalize()).append('\n');
        sb.append("STAMP ").append(System.currentTimeMillis()).append('\n');
        for (String ref : refs) {
            sb.append("REF ").append(ref).append('\n');
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
        return target;
    }

    /** Parse one manifest file. Returns empty if the file is malformed. */
    public static java.util.Optional<Manifest> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return java.util.Optional.empty();
        String project = null;
        Path lockFile = null;
        long stamp = 0;
        List<String> refs = new ArrayList<>();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            if (line.startsWith("PROJECT ")) {
                project = line.substring("PROJECT ".length()).trim();
            } else if (line.startsWith("LOCKFILE ")) {
                lockFile = Path.of(line.substring("LOCKFILE ".length()).trim());
            } else if (line.startsWith("STAMP ")) {
                try {
                    stamp = Long.parseLong(line.substring("STAMP ".length()).trim());
                } catch (NumberFormatException ignored) {
                    return java.util.Optional.empty();
                }
            } else if (line.startsWith("REF ")) {
                refs.add(line.substring("REF ".length()).trim());
            }
        }
        if (project == null) return java.util.Optional.empty();
        return java.util.Optional.of(new Manifest(project, lockFile, stamp, refs));
    }

    /**
     * In-memory view. Mirrors the on-disk shape; intentionally small —
     * sweep callers only need {@link #refs} once a {@code MIN_AGE} check
     * has passed on the file's mtime.
     */
    public record Manifest(String projectFingerprint, Path lockFile, long stampMillis, List<String> refs) {
        public Manifest {
            refs = List.copyOf(refs);
        }
    }

    /**
     * Pull every package's sha256 out of the lockfile. Packages without a
     * checksum (POM-only / path / git) contribute nothing.
     */
    private static Set<String> collectRefs(Lockfile lock) {
        Set<String> sorted = new TreeSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            String checksum = pkg.checksum();
            if (checksum == null || checksum.isBlank()) continue;
            String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
            if (seen.add(hex)) sorted.add(hex);
        }
        return sorted;
    }
}
