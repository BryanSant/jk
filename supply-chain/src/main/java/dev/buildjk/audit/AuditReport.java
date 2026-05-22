// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.audit;

import dev.buildjk.lock.Lockfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregated {@code jk audit} findings (PRD §23.5). Built from a
 * {@link Lockfile} plus the per-package OSV response.
 */
public final class AuditReport {

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW, UNKNOWN;

        public static Severity parse(String raw) {
            if (raw == null) return UNKNOWN;
            String upper = raw.trim().toUpperCase(Locale.ROOT);
            for (Severity s : values()) {
                if (upper.contains(s.name())) return s;
            }
            return UNKNOWN;
        }

        /** True if this severity is at least as severe as {@code threshold}. */
        public boolean atLeast(Severity threshold) {
            return ordinal() <= threshold.ordinal();
        }
    }

    public record Finding(
            String module,
            String version,
            String vulnId,
            String summary,
            Severity severity) {
        public Finding {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(vulnId, "vulnId");
            Objects.requireNonNull(severity, "severity");
            if (summary == null) summary = "";
        }
    }

    private final List<Finding> findings;

    public AuditReport(List<Finding> findings) {
        this.findings = List.copyOf(findings);
    }

    public List<Finding> findings() {
        return findings;
    }

    /** Findings at or above {@code threshold}. */
    public List<Finding> filterAtLeast(Severity threshold) {
        List<Finding> filtered = new ArrayList<>();
        for (Finding f : findings) {
            if (f.severity().atLeast(threshold)) filtered.add(f);
        }
        return filtered;
    }

    /** Count of findings grouped by severity, descending. */
    public Map<Severity, Integer> bySeverity() {
        Map<Severity, Integer> counts = new LinkedHashMap<>();
        for (Severity s : Severity.values()) counts.put(s, 0);
        for (Finding f : findings) {
            counts.merge(f.severity(), 1, Integer::sum);
        }
        return counts;
    }

    public boolean isEmpty() {
        return findings.isEmpty();
    }

    /** Render as Markdown for human consumption. */
    public String renderMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# jk audit\n\n");
        if (findings.isEmpty()) {
            sb.append("No known vulnerabilities in the locked dependencies.\n");
            return sb.toString();
        }
        sb.append(findings.size()).append(" finding")
                .append(findings.size() == 1 ? "" : "s").append(" by severity: ");
        Map<Severity, Integer> counts = bySeverity();
        boolean first = true;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() == 0) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        sb.append("\n\n");
        for (Finding f : findings) {
            sb.append("- **").append(f.severity()).append("** ")
                    .append(f.module()).append(':').append(f.version())
                    .append(" — [").append(f.vulnId()).append("](https://osv.dev/vulnerability/")
                    .append(f.vulnId()).append(')');
            if (!f.summary().isEmpty()) {
                sb.append(" — ").append(f.summary());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
