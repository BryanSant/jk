// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

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
 * Maven-style "is this task up to date?" check. Stamps an action's output
 * directory with the inputs that produced it and the wall time of the
 * write, so a subsequent build can skip the compile when no input has
 * changed.
 *
 * <p>This sits <em>in front</em> of the {@link ActionCache}. The action
 * cache is correct but expensive — it hashes every source file's content
 * to compute its key. The freshness stamp is the opposite trade-off:
 * a single {@code stat} per input file, no reads. When nothing has
 * changed, we get out fast; when something has, we fall through to the
 * action cache, which has a real chance of restoring outputs from CAS
 * even if the local stamp is stale (different machine, cleaned output
 * dir, etc.).
 *
 * <p>The stamp file lives inside {@code outputDir} as
 * {@code .jk-stamp}, so wiping the output tree automatically invalidates
 * the stamp — the build will fall through to the next layer rather than
 * trusting a stamp pointing at deleted classes.
 *
 * <p>Stale detection rules:
 * <ol>
 *   <li>Stamp file missing → not fresh.</li>
 *   <li>Source set or classpath set differs from what the stamp recorded
 *       (file added or removed) → not fresh.</li>
 *   <li>Any input file is missing now → not fresh.</li>
 *   <li>Any input file's mtime is newer than the stamp's recorded write
 *       time → not fresh.</li>
 *   <li>Otherwise → fresh; skip the compile.</li>
 * </ol>
 *
 * <p>Mtimes can be spoofed (a {@code touch} resets mtime; a content swap
 * with preserved mtime defeats us). The action-cache layer behind us
 * catches both because it hashes content. The stamp is an optimisation,
 * not a security boundary.
 */
public final class FreshnessStamp {

    /** Sentinel filename stamped into the output directory. */
    static final String FILE_NAME = ".jk-stamp";

    private FreshnessStamp() {}

    /**
     * Returns {@code true} when {@code outputDir} carries a stamp whose
     * recorded source + classpath sets match the current inputs and no
     * input has been touched since the stamp was written.
     */
    public static boolean isFresh(Path outputDir, List<Path> sources, List<Path> classpath)
            throws IOException {
        Optional<Stamp> read = read(outputDir);
        if (read.isEmpty()) return false;
        Stamp stamp = read.get();

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
     * Write a stamp into {@code outputDir} recording the action that
     * produced its current contents. Called after a successful compile
     * (either fresh or cache-restored) so the next build can short-circuit.
     */
    public static void write(
            Path outputDir,
            String taskId,
            String actionKey,
            List<Path> sources,
            List<Path> classpath) throws IOException {
        Files.createDirectories(outputDir);
        long stampMillis = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("TASK ").append(taskId).append('\n');
        sb.append("KEY ").append(actionKey).append('\n');
        sb.append("STAMP_MILLIS ").append(stampMillis).append('\n');
        for (Path src : sortedAbs(sources)) {
            sb.append("SOURCE ").append(src).append('\n');
        }
        for (Path cp : sortedAbs(classpath)) {
            sb.append("CP ").append(cp).append('\n');
        }
        Files.writeString(outputDir.resolve(FILE_NAME), sb.toString(), StandardCharsets.UTF_8);
    }

    static Optional<Stamp> read(Path outputDir) throws IOException {
        Path file = outputDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return Optional.empty();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String taskId = null;
        String actionKey = null;
        long stampMillis = 0;
        List<Path> sources = new ArrayList<>();
        List<Path> classpath = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;
            if (line.startsWith("TASK ")) {
                taskId = line.substring("TASK ".length()).trim();
            } else if (line.startsWith("KEY ")) {
                actionKey = line.substring("KEY ".length()).trim();
            } else if (line.startsWith("STAMP_MILLIS ")) {
                stampMillis = Long.parseLong(line.substring("STAMP_MILLIS ".length()).trim());
            } else if (line.startsWith("SOURCE ")) {
                sources.add(Path.of(line.substring("SOURCE ".length()).trim()));
            } else if (line.startsWith("CP ")) {
                classpath.add(Path.of(line.substring("CP ".length()).trim()));
            }
        }
        if (taskId == null || actionKey == null) return Optional.empty();
        return Optional.of(new Stamp(taskId, actionKey, stampMillis, sources, classpath));
    }

    public record Stamp(
            String taskId,
            String actionKey,
            long stampMillis,
            List<Path> sources,
            List<Path> classpath) {
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
        return Files.getLastModifiedTime(file).toMillis() > stampMillis;
    }
}
