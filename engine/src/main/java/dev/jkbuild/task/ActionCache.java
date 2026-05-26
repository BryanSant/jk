// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.Linking;
import dev.jkbuild.util.Hashing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Persistent action cache (PRD §17.1). Maps an {@link ActionKey}-style
 * hash to the file outputs the action produced. Outputs themselves live
 * in the {@link Cas}; this class stores the {@code action_key → outputs}
 * mapping alongside a {@code last-by-task → action_key} pointer so
 * {@code jk why-rebuilt} can diff a task's current inputs against its
 * most recent recorded run.
 *
 * <p>On-disk layout:
 * <pre>{@code
 *   <root>/by-key/<actionKey>           one ActionRecord per cached action
 *   <root>/last-by-task/<taskId>        plain text — the most recent actionKey
 * }</pre>
 */
public final class ActionCache {

    private final Cas cas;
    private final Path root;

    public ActionCache(Cas cas, Path root) {
        this.cas = Objects.requireNonNull(cas, "cas");
        this.root = Objects.requireNonNull(root, "root");
    }

    public Optional<ActionRecord> lookup(String actionKey) throws IOException {
        Path file = byKeyDir().resolve(actionKey);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(parse(Files.readString(file)));
    }

    public Optional<ActionRecord> lastFor(String taskId) throws IOException {
        Path pointer = lastByTaskDir().resolve(taskId);
        if (!Files.exists(pointer)) return Optional.empty();
        String actionKey = Files.readString(pointer).trim();
        return lookup(actionKey);
    }

    /**
     * Compute output hashes from {@code outputDir}, deposit each file in
     * the CAS, and write the {@link ActionRecord}. After this, callers
     * can later restore the same outputs via {@link #restore}.
     */
    public ActionRecord store(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Path outputDir) throws IOException {
        Files.createDirectories(byKeyDir());
        Files.createDirectories(lastByTaskDir());

        Map<String, String> outputs = new TreeMap<>();
        if (Files.exists(outputDir)) {
            try (Stream<Path> stream = Files.walk(outputDir)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(file)) continue;
                    // Hash once, then hard-link the file into the CAS rather
                    // than re-reading + writing the bytes. On POSIX same-fs
                    // the output file in target/ and the CAS object share an
                    // inode from this point on; the storage cost of caching
                    // is zero. Cross-fs falls back to a byte copy via
                    // Cas.putByLink → Linking.linkOrCopy.
                    byte[] bytes = Files.readAllBytes(file);
                    String hex = Hashing.sha256Hex(bytes);
                    cas.putByLink(file, hex);
                    String relPath = outputDir.relativize(file).toString()
                            .replace(File.separatorChar, '/');
                    outputs.put(relPath, hex);
                }
            }
        }
        ActionRecord record = new ActionRecord(taskId, actionKey, inputs, outputs);
        Files.writeString(byKeyDir().resolve(actionKey), render(record));
        Files.writeString(lastByTaskDir().resolve(taskId), actionKey);
        return record;
    }

    /**
     * Clear the contents of {@code outputDir} and copy each cached output
     * back from the CAS. Stale files from a prior compile are removed
     * before restoring.
     */
    public void restore(ActionRecord record, Path outputDir) throws IOException {
        if (Files.exists(outputDir)) {
            deleteRecursively(outputDir);
        }
        Files.createDirectories(outputDir);
        for (Map.Entry<String, String> entry : record.outputs().entrySet()) {
            Path target = outputDir.resolve(entry.getKey());
            // Hard-link from the CAS object on POSIX same-fs; cross-fs and
            // Windows fall back to a copy automatically. Cuts restore cost
            // for large classes/ trees from O(bytes) to O(entries).
            Linking.linkOrCopy(cas.pathFor(entry.getValue()), target);
        }
    }

    // --- record + serialization --------------------------------------------

    public record ActionRecord(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs) {

        public ActionRecord {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(actionKey, "actionKey");
            inputs = Map.copyOf(inputs);
            outputs = Map.copyOf(outputs);
        }
    }

    private static String render(ActionRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK ").append(record.taskId()).append('\n');
        sb.append("KEY ").append(record.actionKey()).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(record.inputs()).entrySet()) {
            sb.append("INPUT ").append(e.getValue()).append(' ').append(e.getKey()).append('\n');
        }
        for (Map.Entry<String, String> e : new TreeMap<>(record.outputs()).entrySet()) {
            sb.append("OUTPUT ").append(e.getValue()).append(' ').append(e.getKey()).append('\n');
        }
        return sb.toString();
    }

    private static ActionRecord parse(String content) {
        String taskId = null;
        String actionKey = null;
        Map<String, String> inputs = new LinkedHashMap<>();
        Map<String, String> outputs = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;
            if (line.startsWith("TASK ")) {
                taskId = line.substring("TASK ".length()).trim();
            } else if (line.startsWith("KEY ")) {
                actionKey = line.substring("KEY ".length()).trim();
            } else if (line.startsWith("INPUT ")) {
                String body = line.substring("INPUT ".length());
                int sp = body.indexOf(' ');
                inputs.put(body.substring(sp + 1), body.substring(0, sp));
            } else if (line.startsWith("OUTPUT ")) {
                String body = line.substring("OUTPUT ".length());
                int sp = body.indexOf(' ');
                outputs.put(body.substring(sp + 1), body.substring(0, sp));
            }
        }
        return new ActionRecord(
                Objects.requireNonNull(taskId, "taskId in record"),
                Objects.requireNonNull(actionKey, "actionKey in record"),
                inputs, outputs);
    }

    private Path byKeyDir() {
        return root.resolve("by-key");
    }

    private Path lastByTaskDir() {
        return root.resolve("last-by-task");
    }

    private static void deleteRecursively(Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(target)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }
}
