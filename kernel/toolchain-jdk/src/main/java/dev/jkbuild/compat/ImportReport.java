// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-import diagnostics shared by Maven and Gradle importers (PRD §24.2 / §24.3 three-tier
 * fidelity). {@code WARNING} entries correspond to Tier 2 (best-effort mapping); {@code ERROR}
 * entries correspond to Tier 3 (construct could not be carried over and the import emitted a stub
 * or skipped it).
 *
 * <p>Renders as {@code jk-import-report.md} so contributors can review what survived the
 * conversion.
 */
public final class ImportReport {

    public enum Severity {
        WARNING,
        ERROR
    }

    public record Issue(Severity severity, String message) {}

    private final List<Issue> issues;

    private ImportReport(List<Issue> issues) {
        this.issues = List.copyOf(issues);
    }

    public List<Issue> issues() {
        return issues;
    }

    public boolean isEmpty() {
        return issues.isEmpty();
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
    }

    /** Renders the report as Markdown, suitable for writing to {@code jk-import-report.md}. */
    public String renderMarkdown(String sourceFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("# jk import report\n\n");
        sb.append("Source: `").append(sourceFile).append("`\n\n");
        if (issues.isEmpty()) {
            sb.append("Import was lossless — no warnings.\n");
            return sb.toString();
        }
        List<Issue> warnings =
                issues.stream().filter(i -> i.severity() == Severity.WARNING).toList();
        List<Issue> errors =
                issues.stream().filter(i -> i.severity() == Severity.ERROR).toList();
        if (!errors.isEmpty()) {
            sb.append("## Tier 3 — not imported\n\n");
            sb.append("These constructs have no jk equivalent and were skipped or stubbed.\n\n");
            for (Issue e : errors) {
                sb.append("- ").append(e.message()).append('\n');
            }
            sb.append('\n');
        }
        if (!warnings.isEmpty()) {
            sb.append("## Tier 2 — imported with best-effort\n\n");
            sb.append("These were mapped but you should review the result.\n\n");
            for (Issue w : warnings) {
                sb.append("- ").append(w.message()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Issue> issues = new ArrayList<>();

        public Builder warning(String message) {
            issues.add(new Issue(Severity.WARNING, message));
            return this;
        }

        public Builder error(String message) {
            issues.add(new Issue(Severity.ERROR, message));
            return this;
        }

        public ImportReport build() {
            return new ImportReport(issues);
        }
    }
}
