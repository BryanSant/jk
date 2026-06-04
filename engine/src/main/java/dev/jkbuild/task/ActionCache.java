// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.Linking;
import dev.jkbuild.util.Hashing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * mapping alongside a {@code task → action_key} pointer so a future
 * {@code jk why-rebuilt} can diff a task's current inputs against its
 * most recent recorded run.
 *
 * <p>On-disk layout:
 * <pre>{@code
 *   <root>/keys/<actionKey>     one ActionRecord per cached action
 *   <root>/tasks/<taskId>       plain text — the most recent actionKey
 * }</pre>
 *
 * <p>{@code taskId} is expected to be project-qualified by the caller (see
 * {@link ActionKey#qualifiedTaskId}) so the {@code tasks/} pointer for, say,
 * {@code compile-main} doesn't collide across projects / workspace members.
 */
public final class ActionCache {

    private final Cas cas;
    private final Path root;

    public ActionCache(Cas cas, Path root) {
        this.cas = Objects.requireNonNull(cas, "cas");
        this.root = Objects.requireNonNull(root, "root");
    }

    public Optional<ActionRecord> lookup(String actionKey) throws IOException {
        Path file = keysDir().resolve(actionKey);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(parse(Files.readString(file)));
    }

    public Optional<ActionRecord> lastFor(String taskId) throws IOException {
        Path pointer = tasksDir().resolve(taskId);
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
        Map<String, String> outputs = new TreeMap<>();
        if (Files.exists(outputDir)) {
            try (Stream<Path> stream = Files.walk(outputDir)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(file)) continue;
                    // FreshnessStamp's sentinels (.jstamp/.kstamp) live inside
                    // outputDir but aren't action outputs — exclude them so we
                    // don't accidentally cache a stamp from a previous run.
                    if (FreshnessStamp.isStampFile(file.getFileName().toString())) continue;
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
        return storeWithOutputs(taskId, actionKey, inputs, outputs);
    }

    /**
     * Write an action record using a pre-computed {@code outputs} map —
     * used by callers that already CAS'd the files via {@link CasPrewriter}
     * (or anything else that hashed + hard-linked while the action was
     * still running). Skips the output-dir walk; just writes the manifest
     * and pointer.
     */
    public ActionRecord storeWithOutputs(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs) throws IOException {
        return storeWithOutputs(taskId, actionKey, inputs, outputs, Map.of());
    }

    /** As above, plus the per-source {@code units} grouping (incremental builds). */
    public ActionRecord storeWithOutputs(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Map<String, List<String>> units) throws IOException {
        Files.createDirectories(keysDir());
        Files.createDirectories(tasksDir());
        ActionRecord record = new ActionRecord(taskId, actionKey, inputs, outputs, units);
        Files.writeString(keysDir().resolve(actionKey), render(record));
        Files.writeString(tasksDir().resolve(taskId), actionKey);
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
        AccessLedger ledger = new AccessLedger(cas.root());
        for (Map.Entry<String, String> entry : record.outputs().entrySet()) {
            Path target = outputDir.resolve(entry.getKey());
            // Hard-link from the CAS object on POSIX same-fs; cross-fs and
            // Windows fall back to a copy automatically. Cuts restore cost
            // for large classes/ trees from O(bytes) to O(entries).
            Linking.linkOrCopy(cas.pathFor(entry.getValue()), target);
            // Best-effort access journal — feeds the LRU evictor when the
            // user configures a cache size budget.
            ledger.touch(entry.getValue());
        }
    }

    // --- record + serialization --------------------------------------------

    public record ActionRecord(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Map<String, List<String>> units) {

        public ActionRecord {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(actionKey, "actionKey");
            inputs = Map.copyOf(inputs);
            outputs = Map.copyOf(outputs);
            // units: source-abs-path → output relPaths it produced. Populated by
            // an incremental compiler; empty for full rebuilds / legacy records.
            Map<String, List<String>> u = new LinkedHashMap<>();
            if (units != null) units.forEach((k, v) -> u.put(k, List.copyOf(v)));
            units = Map.copyOf(u);
        }

        /** Back-compat: a record with no per-source unit grouping. */
        public ActionRecord(String taskId, String actionKey,
                            Map<String, String> inputs, Map<String, String> outputs) {
            this(taskId, actionKey, inputs, outputs, Map.of());
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
        // UNIT <relPath> <sourceAbsPath> — relPath is space-free (Java class
        // path), source is the rest of the line so it may contain spaces.
        for (Map.Entry<String, List<String>> e : new TreeMap<>(record.units()).entrySet()) {
            List<String> rels = new java.util.ArrayList<>(e.getValue());
            rels.sort(Comparator.naturalOrder());
            for (String rel : rels) {
                sb.append("UNIT ").append(rel).append(' ').append(e.getKey()).append('\n');
            }
        }
        return sb.toString();
    }

    private static ActionRecord parse(String content) {
        String taskId = null;
        String actionKey = null;
        Map<String, String> inputs = new LinkedHashMap<>();
        Map<String, String> outputs = new LinkedHashMap<>();
        Map<String, List<String>> units = new LinkedHashMap<>();
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
            } else if (line.startsWith("UNIT ")) {
                // UNIT <relPath> <sourceAbsPath> — absent in legacy records.
                String body = line.substring("UNIT ".length());
                int sp = body.indexOf(' ');
                String rel = body.substring(0, sp);
                String source = body.substring(sp + 1);
                units.computeIfAbsent(source, k -> new ArrayList<>()).add(rel);
            }
        }
        return new ActionRecord(
                Objects.requireNonNull(taskId, "taskId in record"),
                Objects.requireNonNull(actionKey, "actionKey in record"),
                inputs, outputs, units);
    }

    private Path keysDir() {
        return root.resolve("keys");
    }

    private Path tasksDir() {
        return root.resolve("tasks");
    }

    private static void deleteRecursively(Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(target)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }
}
