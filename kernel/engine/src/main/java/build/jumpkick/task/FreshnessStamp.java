// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Maven-style "is this task up to date?" check. Stamps an action's output directory with the inputs
 * that produced it and the wall time of the write, so a subsequent build can skip the compile when
 * no input has changed.
 *
 * <p>This sits <em>in front</em> of the {@link ActionCache}. The action cache is correct but
 * expensive — it hashes every source file's content to compute its key. The freshness stamp is the
 * opposite trade-off: a single {@code stat} per input file, no reads. When nothing has changed, we
 * get out fast; when something has, we fall through to the action cache, which has a real chance of
 * restoring outputs from CAS even if the local stamp is stale (different machine, cleaned output
 * dir, etc.).
 *
 * <p>The stamp file lives inside {@code outputDir} — {@link #JAVA_STAMP} for the Java compile,
 * {@link #KOTLIN_STAMP} for Kotlin — so wiping the output tree automatically invalidates the stamp,
 * and the two languages stamp independently into a shared output dir without clobbering each other.
 * The build will fall through to the next layer rather than trusting a stamp pointing at deleted
 * classes.
 *
 * <p>Stale detection rules:
 *
 * <ol>
 *   <li>Stamp file missing → not fresh.
 *   <li>Source set or classpath set differs from what the stamp recorded (file added or removed) →
 *       not fresh.
 *   <li>Any input file is missing now → not fresh.
 *   <li>Any input file's mtime is at or after the stamp's recorded write time → not fresh.
 *       (At-or-after, not strictly-after: mtimes are millisecond-truncated, so a same-millisecond
 *       edit must be treated as a possible change and pushed to the content-hash layer.)
 *   <li>Otherwise → fresh; skip the compile.
 * </ol>
 *
 * <p>Mtimes can be spoofed (a {@code touch} resets mtime; a content swap with preserved mtime
 * defeats us). The action-cache layer behind us catches both because it hashes content. The stamp
 * is an optimisation, not a security boundary.
 */
public final class FreshnessStamp {

    /** Sentinel stamped into the output directory by the Java compile. */
    public static final String JAVA_STAMP = ".jstamp";

    /** Sentinel stamped into the output directory by the Kotlin compile. */
    public static final String KOTLIN_STAMP = ".kstamp";

    private FreshnessStamp() {}

    /**
     * True when {@code fileName} is one of our compile stamp sentinels. Used by {@link ActionCache}
     * (and the incremental compile/prewrite snapshots) to exclude stamps when capturing outputs — a
     * stamp is build metadata, not an action output. (The test result is no longer a file; it's a CAS
     * marker — see {@link TestStamp}.)
     */
    public static boolean isStampFile(String fileName) {
        return JAVA_STAMP.equals(fileName) || KOTLIN_STAMP.equals(fileName);
    }

    /**
     * Returns {@code true} when {@code outputDir} carries a stamp whose recorded source + classpath
     * sets match the current inputs and no input has been touched since the stamp was written.
     */
    public static boolean isFresh(
            Path outputDir, String stampName, List<Path> sources, List<Path> classpath, int release)
            throws IOException {
        Optional<Stamp> read = read(outputDir, stampName);
        if (read.isEmpty()) return false;
        Stamp stamp = read.get();

        // A stamp written before release tracking was added has release=0; treat as stale.
        if (stamp.release() != release) return false;

        Set<Path> currentSrc = normalise(sources);
        Set<Path> recordedSrc = normalise(stamp.sources());
        if (!currentSrc.equals(recordedSrc)) return false;

        Set<Path> currentCp = normalise(classpath);
        Set<Path> recordedCp = normalise(stamp.classpath());
        if (!currentCp.equals(recordedCp)) return false;

        for (Path src : currentSrc) {
            if (newerThan(src, stamp.stampMillis())) return false;
        }
        for (Path cp : currentCp) {
            if (newerThan(cp, stamp.stampMillis())) return false;
        }
        return true;
    }

    /**
     * True when the stamp recorded a source that is NOT in the current set — the source set
     * shrank (a variant switch dropping an extra-src root, a deleted file). The caller must
     * start the output tree clean: kotlinc's incremental engine prunes its own dir, but the
     * assemble merge into the shared classes tree is additive, so a dropped source's classes
     * would otherwise survive into the packaged artifact.
     */
    public static boolean hasRemovedSources(Path outputDir, String stampName, List<Path> currentSources) {
        try {
            Optional<Stamp> read = read(outputDir, stampName);
            if (read.isEmpty()) return false;
            Set<Path> current = normalise(currentSources);
            for (Path recorded : normalise(read.get().sources())) {
                if (!current.contains(recorded)) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Cheap, classpath-free freshness probe for progress-weight prediction: {@code true} when a stamp
     * exists and no source has been touched since it was written. Unlike {@link #isFresh} it doesn't
     * compare the source/classpath <em>sets</em> (the predictor has no resolved classpath), so a
     * dependency-set change can slip through — acceptable for sizing the bar, where any misprediction
     * is closed by step-end auto-fill.
     */
    public static boolean looksFresh(Path outputDir, String stampName, List<Path> sources) {
        try {
            Optional<Stamp> read = read(outputDir, stampName);
            if (read.isEmpty()) return false;
            long stampMillis = read.get().stampMillis();
            for (Path src : sources) {
                if (newerThan(src, stampMillis)) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Write a stamp into {@code outputDir} recording the action that produced its current contents.
     * Called after a successful compile (either fresh or cache-restored) so the next build can
     * short-circuit.
     */
    public static void write(
            Path outputDir,
            String stampName,
            String taskId,
            String actionKey,
            List<Path> sources,
            List<Path> classpath,
            int release)
            throws IOException {
        Files.createDirectories(outputDir);
        long stampMillis = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("TASK ").append(taskId).append('\n');
        sb.append("KEY ").append(actionKey).append('\n');
        sb.append("STAMP_MILLIS ").append(stampMillis).append('\n');
        sb.append("RELEASE ").append(release).append('\n');
        for (Path src : sortedAbs(sources)) {
            sb.append("SOURCE ").append(src).append('\n');
        }
        for (Path cp : sortedAbs(classpath)) {
            sb.append("CP ").append(cp).append('\n');
        }
        Files.writeString(outputDir.resolve(stampName), sb.toString(), StandardCharsets.UTF_8);
    }

    static Optional<Stamp> read(Path outputDir, String stampName) throws IOException {
        Path file = outputDir.resolve(stampName);
        if (!Files.isRegularFile(file)) return Optional.empty();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String taskId = null;
        String actionKey = null;
        long stampMillis = 0;
        int release = 0;
        List<Path> sources = new ArrayList<>();
        List<Path> classpath = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;
            if (line.startsWith("TASK ")) {
                taskId = line.substring("TASK ".length()).trim();
            } else if (line.startsWith("KEY ")) {
                actionKey = line.substring("KEY ".length()).trim();
            } else if (line.startsWith("STAMP_MILLIS ")) {
                stampMillis =
                        Long.parseLong(line.substring("STAMP_MILLIS ".length()).trim());
            } else if (line.startsWith("RELEASE ")) {
                release = Integer.parseInt(line.substring("RELEASE ".length()).trim());
            } else if (line.startsWith("SOURCE ")) {
                sources.add(Path.of(line.substring("SOURCE ".length()).trim()));
            } else if (line.startsWith("CP ")) {
                classpath.add(Path.of(line.substring("CP ".length()).trim()));
            }
        }
        if (taskId == null || actionKey == null) return Optional.empty();
        return Optional.of(new Stamp(taskId, actionKey, stampMillis, release, sources, classpath));
    }

    public record Stamp(
            String taskId, String actionKey, long stampMillis, int release, List<Path> sources, List<Path> classpath) {
        public Stamp {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(actionKey, "actionKey");
            sources = List.copyOf(sources);
            classpath = List.copyOf(classpath);
        }
    }

    private static Set<Path> normalise(List<Path> paths) {
        Set<Path> out = new TreeSet<>();
        for (Path p : paths) out.add(p.toAbsolutePath().normalize());
        return out;
    }

    private static List<Path> sortedAbs(List<Path> paths) {
        List<Path> copy = new ArrayList<>(paths.size());
        for (Path p : paths) copy.add(p.toAbsolutePath().normalize());
        copy.sort(Comparator.comparing(Path::toString));
        return copy;
    }

    private static boolean newerThan(Path file, long stampMillis) throws IOException {
        if (!Files.exists(file)) return true; // disappearing input → treat as changed
        // Use >= , not > : filesystem mtimes are millisecond-truncated, and a
        // build can finish writing its stamp in the same millisecond a source
        // is edited (fast disks, tiny projects). Treating "mtime == stampMillis"
        // as potentially-changed means we distrust the cheap stat at that
        // boundary and fall through to the content-hashing action cache, which
        // decides correctly. With strict > , a same-millisecond edit is silently
        // skipped — a stale-build bug, not just a test flake.
        return Files.getLastModifiedTime(file).toMillis() >= stampMillis;
    }
}
