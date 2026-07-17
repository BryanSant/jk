// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.util.MinimalXml;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe accumulator for per-test results that writes one Gradle-compatible {@code
 * TEST-<classname>.xml} file per test class to a target directory.
 *
 * <p>In parallel mode all workers share one instance; synchronization is on the entry list. {@link
 * #writeAll(Path)} is called once after all workers have joined.
 *
 * <p>The output format matches Gradle's JUnit XML report schema so the files are consumable by CI
 * dashboards, IntelliJ, and test-aggregation tools without additional configuration.
 */
public final class XmlTestReport {

    private record Entry(
            String className,
            String displayName,
            long durationMs,
            String failureType,
            String failureMessage,
            String failureStack,
            String skipReason) {}

    private final List<Entry> entries = new ArrayList<>();
    private final String timestamp;
    private final String hostname;

    public XmlTestReport() {
        this.timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            host = "localhost";
        }
        this.hostname = host;
    }

    /**
     * Record a finished test (passed, failed, or aborted). {@code throwableJson} is the raw nested
     * JSON object from the protocol event's {@code throwable} field — {@code null} for a passing
     * test.
     */
    public synchronized void recordFinished(String uniqueId, String display, long durationMs, String throwableJson) {
        String className = classNameFrom(uniqueId);
        String failureType = null, failureMessage = null, failureStack = null;
        if (throwableJson != null) {
            failureType = Jsonl.str(throwableJson, "class");
            failureMessage = Jsonl.str(throwableJson, "message");
            failureStack = Jsonl.str(throwableJson, "stack");
        }
        entries.add(new Entry(className, display, durationMs, failureType, failureMessage, failureStack, null));
    }

    /**
     * Record a skipped test. {@code reason} is the skip reason from the protocol event, may be null.
     */
    public synchronized void recordSkipped(String uniqueId, String display, String reason) {
        String className = classNameFrom(uniqueId);
        entries.add(new Entry(className, display, 0, null, null, null, reason != null ? reason : ""));
    }

    /**
     * Write one {@code TEST-<classname>.xml} per accumulated class into {@code dir}, creating the
     * directory if needed. Silently no-ops when no test events were recorded.
     */
    public synchronized void writeAll(Path dir) throws IOException {
        if (entries.isEmpty()) return;
        Files.createDirectories(dir);

        Map<String, List<Entry>> byClass = new LinkedHashMap<>();
        for (Entry e : entries) {
            byClass.computeIfAbsent(e.className(), k -> new ArrayList<>()).add(e);
        }

        for (var kv : byClass.entrySet()) {
            Path file = dir.resolve("TEST-" + kv.getKey() + ".xml");
            Files.writeString(file, buildXml(kv.getKey(), kv.getValue()));
        }
    }

    private String buildXml(String className, List<Entry> classEntries) {
        int tests = classEntries.size(), failures = 0, skipped = 0;
        long totalMs = 0;
        for (Entry e : classEntries) {
            totalMs += e.durationMs();
            if (e.failureType() != null) failures++;
            if (e.skipReason() != null) skipped++;
        }

        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"").append(esc(className)).append('"');
        sb.append(" tests=\"").append(tests).append('"');
        sb.append(" skipped=\"").append(skipped).append('"');
        sb.append(" failures=\"").append(failures).append('"');
        sb.append(" errors=\"0\"");
        sb.append(" timestamp=\"").append(timestamp).append('"');
        sb.append(" hostname=\"").append(esc(hostname)).append('"');
        sb.append(" time=\"").append(String.format("%.3f", totalMs / 1000.0)).append("\">\n");
        sb.append("  <properties/>\n");

        for (Entry e : classEntries) {
            sb.append("  <testcase name=\"").append(esc(e.displayName())).append('"');
            sb.append(" classname=\"").append(esc(e.className())).append('"');
            sb.append(" time=\"")
                    .append(String.format("%.3f", e.durationMs() / 1000.0))
                    .append('"');
            if (e.skipReason() != null) {
                sb.append(">\n    <skipped");
                if (!e.skipReason().isEmpty())
                    sb.append(" message=\"").append(esc(e.skipReason())).append('"');
                sb.append("/>\n  </testcase>\n");
            } else if (e.failureType() != null) {
                sb.append(">\n    <failure");
                if (e.failureMessage() != null && !e.failureMessage().isEmpty())
                    sb.append(" message=\"").append(esc(e.failureMessage())).append('"');
                sb.append(" type=\"").append(esc(e.failureType())).append('"').append('>');
                if (e.failureStack() != null && !e.failureStack().isEmpty()) sb.append(cdata(e.failureStack()));
                sb.append("</failure>\n  </testcase>\n");
            } else {
                sb.append("/>\n");
            }
        }

        sb.append("  <system-out><![CDATA[]]></system-out>\n");
        sb.append("  <system-err><![CDATA[]]></system-err>\n");
        sb.append("</testsuite>\n");
        return sb.toString();
    }

    /**
     * Extract the fully-qualified class name from a JUnit Platform uniqueId. Example: {@code
     * [engine:junit-jupiter][class:com.example.FooTest][method:bar()]} → {@code com.example.FooTest}.
     * Falls back to the raw uniqueId when the {@code [class:...]} segment is absent.
     */
    static String classNameFrom(String uniqueId) {
        int s = uniqueId.indexOf("[class:");
        if (s < 0) return uniqueId;
        int e = uniqueId.indexOf(']', s);
        if (e < 0) return uniqueId;
        return uniqueId.substring(s + 7, e);
    }

    private static String esc(String s) {
        return s == null ? "" : MinimalXml.escapeAttr(s);
    }

    private static String cdata(String s) {
        return "<![CDATA[" + s.replace("]]>", "]]]]><![CDATA[>") + "]]>";
    }
}
