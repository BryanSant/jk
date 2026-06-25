// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test;

import dev.jkbuild.plugin.protocol.Ndjson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe accumulator for per-test results that writes a single
 * {@code test-results.md} into the test-results directory.
 *
 * <p>The file mimics the Jenkins Test Results page layout:
 * <ul>
 *   <li>A one-line summary (total failures, total tests, wall-clock duration)</li>
 *   <li>An "All Failed Tests" section — failures grouped by class, each with its
 *       stack trace in a fenced code block</li>
 *   <li>An "All Tests" table — one row per package with Fail / Skip / Pass /
 *       Total counts</li>
 * </ul>
 *
 * <p>In parallel mode all workers share one instance; synchronization is on
 * the entry list. {@link #writeAll(Path)} is called once after all workers
 * have joined, so the full result set is available before any rendering starts.
 *
 * <p>Companion to {@link XmlTestReport}: both writers share the same event
 * stream; the XML files provide CI/IDE compatibility while this file provides
 * a human- and AI-readable summary.
 */
public final class MarkdownTestReport {

    private record Entry(String className, String displayName, long durationMs,
                         String failureMessage, String failureStack,
                         String skipReason) {
        boolean isFail() { return failureMessage != null || failureStack != null; }
        boolean isSkip() { return skipReason != null; }
        boolean isPass() { return !isFail() && !isSkip(); }
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Record a finished test (passed, failed, or aborted).
     * {@code throwableJson} is the raw nested JSON object from the protocol
     * event's {@code throwable} field — {@code null} for a passing test.
     */
    public synchronized void recordFinished(String uniqueId, String display,
                                            long durationMs, String throwableJson) {
        String className = classNameFrom(uniqueId);
        String failureMessage = null, failureStack = null;
        if (throwableJson != null) {
            failureMessage = Ndjson.str(throwableJson, "message");
            failureStack   = Ndjson.str(throwableJson, "stack");
            if ((failureMessage == null || failureMessage.isBlank()) && failureStack == null) {
                failureMessage = Ndjson.str(throwableJson, "class");
            }
        }
        entries.add(new Entry(className, display, durationMs,
                failureMessage, failureStack, null));
    }

    /**
     * Record a skipped test.
     * {@code reason} is the skip reason from the protocol event, may be null.
     */
    public synchronized void recordSkipped(String uniqueId, String display, String reason) {
        String className = classNameFrom(uniqueId);
        entries.add(new Entry(className, display, 0,
                null, null, reason != null ? reason : ""));
    }

    /**
     * Write {@code test-results.md} into {@code dir}, creating it if needed.
     * No-ops when no test events were recorded.
     */
    public synchronized void writeAll(Path dir) throws IOException {
        if (entries.isEmpty()) return;
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("test-results.md"), buildMarkdown());
    }

    private String buildMarkdown() {
        long failures = entries.stream().filter(Entry::isFail).count();
        long total    = entries.size();
        long totalMs  = entries.stream().mapToLong(Entry::durationMs).sum();

        var sb = new StringBuilder();
        int passRate = total == 0 ? 100
                : (int) Math.round((double)(total - failures) / total * 100);

        // ── Header + Summary ─────────────────────────────────────────────────
        sb.append("# Test Results\n\n");
        sb.append("## Summary\n");
        sb.append("#### **").append(passRate).append("%** Pass Rate · ");
        if (failures == 0) {
            sb.append("No failures for **").append(total).append("** ")
              .append(total == 1 ? "test" : "tests");
        } else if (total == 1) {
            sb.append("**1 failure** out of **1** test");
        } else {
            sb.append("**").append(failures)
              .append(failures == 1 ? " failure**" : " failures**")
              .append(" out of **").append(total).append("** tests");
        }
        sb.append(" · _took ").append(fmtDuration(totalMs)).append("_\n\n");

        // ── Package table ─────────────────────────────────────────────────────
        Map<String, long[]> byPkg = new LinkedHashMap<>();
        for (Entry e : entries) {
            long[] c = byPkg.computeIfAbsent(packageOf(e.className()), k -> new long[4]);
            c[3]++;
            if (e.isFail())      c[0]++;
            else if (e.isSkip()) c[1]++;
            else                 c[2]++;
        }
        sb.append("| Package | Fail | Skip | Pass | Total |\n");
        sb.append("|---|---|---|---|---|\n");
        for (var kv : byPkg.entrySet()) {
            long[] c = kv.getValue();
            sb.append("| ").append(kv.getKey())
              .append(" | ").append(c[0])
              .append(" | ").append(c[1])
              .append(" | ").append(c[2])
              .append(" | ").append(c[3])
              .append(" |\n");
        }

        // ── Failed Tests ─────────────────────────────────────────────────────
        if (failures > 0) {
            sb.append("\n## Failed Tests\n");
            Map<String, List<Entry>> byClass = new LinkedHashMap<>();
            for (Entry e : entries) {
                if (e.isFail()) {
                    byClass.computeIfAbsent(e.className(), k -> new ArrayList<>()).add(e);
                }
            }
            for (var kv : byClass.entrySet()) {
                sb.append("### ").append(kv.getKey()).append("\n");
                for (Entry e : kv.getValue()) {
                    sb.append("#### `").append(e.displayName()).append("`")
                      .append(" — _took ").append(fmtDuration(e.durationMs())).append("_\n");
                    String detail = e.failureStack() != null && !e.failureStack().isBlank()
                            ? e.failureStack().trim()
                            : (e.failureMessage() != null ? e.failureMessage().trim() : "");
                    if (!detail.isEmpty()) {
                        sb.append("```\n").append(detail).append("\n```\n\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    private static String packageOf(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(0, dot);
    }

    /** Extract the FQCN from a JUnit Platform uniqueId. */
    static String classNameFrom(String uniqueId) {
        int s = uniqueId.indexOf("[class:");
        if (s < 0) return uniqueId;
        int e = uniqueId.indexOf(']', s);
        if (e < 0) return uniqueId;
        return uniqueId.substring(s + 7, e);
    }

    private static String fmtDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }
}
