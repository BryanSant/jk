// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append-only journal of JDK usage events. Lives at {@code $JK_JDKS_DIR/.access.log} (default:
 * {@code ~/.jk/jdks/.access.log}).
 *
 * <p>Purpose: build the long-tail "which JDKs does this user actually use, and how often" signal
 * that future wizards lean on:
 *
 * <ul>
 *   <li>{@code jk jdk list --by-usage} — sort frequently-used first.
 *   <li>{@code jk jdk default} wizard — surface preferences as defaults.
 *   <li>{@code jk jdk uninstall} wizard — flag dormant installs as removal candidates ("last used N
 *       days ago").
 * </ul>
 *
 * <p>This commit only writes the journal. Wizard surfacing lands in follow-ups so the data can
 * build up first.
 *
 * <p>Format (text, append-only):
 *
 * <pre>{@code
 * <epoch-millis>\t<event>\t<jdkIdentifier>
 * }</pre>
 *
 * <p>Events:
 *
 * <ul>
 *   <li>{@code resolve} — JDK was resolved for a project build / run / test (the "used" signal).
 *   <li>{@code install} — JDK was just installed via {@code jk jdk install}.
 *   <li>{@code default-set} — JDK was promoted to system default.
 *   <li>{@code pin} — JDK was pinned via {@code jk jdk pin}.
 * </ul>
 *
 * <p>Best-effort: every method swallows IO errors. A missed event just means slightly worse usage
 * signal; nothing breaks.
 */
public final class JdkAccessLedger {

    /** Default file name inside the jdks directory. */
    public static final String FILE_NAME = ".access.log";

    private static final long COMPACT_THRESHOLD_BYTES = 1L * 1024 * 1024; // 1 MiB

    private final Path file;

    /**
     * Default-path constructor — writes under {@link JkDirs#jdksDir()}. Callers that need a custom
     * path (tests) use {@link #JdkAccessLedger(Path)}.
     */
    public static JdkAccessLedger atDefaultPath() {
        return new JdkAccessLedger(JkDirs.jdks().resolve(FILE_NAME));
    }

    public JdkAccessLedger(Path file) {
        this.file = file;
    }

    /**
     * Record that {@code identifier} was just accessed with {@code event}. Single-line append; no
     * exceptions leak.
     */
    public void touch(String identifier, String event) {
        if (identifier == null || identifier.isBlank()) return;
        if (event == null || event.isBlank()) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            String line = System.currentTimeMillis() + "\t" + event + "\t" + identifier + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best-effort.
        }
    }

    /** Per-identifier latest access record across all events. */
    public Map<String, Entry> latestByIdentifier() throws IOException {
        Map<String, Entry> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length != 3) continue;
            long millis;
            try {
                millis = Long.parseLong(parts[0]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            String event = parts[1];
            String id = parts[2].trim();
            Entry prev = out.get(id);
            if (prev == null || prev.millis < millis) {
                int count = prev == null ? 1 : prev.count + 1;
                out.put(id, new Entry(id, event, millis, count));
            } else {
                out.put(id, new Entry(id, prev.event, prev.millis, prev.count + 1));
            }
        }
        return out;
    }

    /**
     * Rewrite as one line per identifier (latest event + millis + total count). Idempotent; no-op
     * below 1 MiB.
     */
    public long compactIfLarge() throws IOException {
        if (!Files.isRegularFile(file)) return 0;
        if (Files.size(file) < COMPACT_THRESHOLD_BYTES) return Files.size(file);
        Map<String, Entry> latest = latestByIdentifier();
        // Stable ordering for diff-ability.
        Map<String, Entry> sorted = new LinkedHashMap<>();
        latest.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        StringBuilder sb = new StringBuilder();
        for (Entry e : sorted.values()) {
            // Compaction loses fine-grained per-touch history (intentional);
            // the rollup is what wizards consume.
            sb.append(e.millis)
                    .append('\t')
                    .append(e.event)
                    .append('\t')
                    .append(e.identifier)
                    .append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".compact");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(
                tmp,
                file,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return Files.size(file);
    }

    /** Ordered "most-recently-used first" view, useful for wizards. */
    public java.util.List<Entry> mostRecentFirst() throws IOException {
        return latestByIdentifier().values().stream()
                .sorted(Comparator.comparingLong((Entry e) -> e.millis).reversed())
                .toList();
    }

    /**
     * One row in the rolled-up view. {@code count} is the number of times this identifier appears
     * anywhere in the journal; {@code event} + {@code millis} are the latest occurrence.
     */
    public record Entry(String identifier, String event, long millis, int count) {}
}
