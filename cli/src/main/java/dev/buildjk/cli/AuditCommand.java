// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.audit.AuditReport;
import dev.buildjk.audit.Auditor;
import dev.buildjk.audit.OsvClient;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk audit} — scan the lockfile against OSV (PRD §23.5). Exits
 * non-zero when any finding at or above the configured severity threshold
 * is present.
 */
@Command(name = "audit", description = "Check the locked dependencies for known vulnerabilities (OSV)")
public final class AuditCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--severity",
            description = "Fail when any finding is at least this severe. "
                    + "One of CRITICAL, HIGH, MEDIUM, LOW. Default: LOW.")
    String severity = "LOW";

    @Option(names = "--osv-batch-url", hidden = true,
            description = "Override the OSV batch query URL (for tests).")
    URI osvBatchUrl;

    @Option(names = "--osv-vulns-url", hidden = true,
            description = "Override the OSV vulnerability lookup URL (for tests).")
    URI osvVulnsUrl;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = directory != null
                ? directory : Path.of(".").toAbsolutePath().normalize();
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(lockPath)) {
            System.err.println("jk audit: no jk.lock in " + projectDir
                    + " (run `jk lock` first).");
            return 2;
        }

        Lockfile lock = LockfileReader.read(lockPath);
        AuditReport.Severity threshold = AuditReport.Severity.parse(severity);
        OsvClient client = (osvBatchUrl != null || osvVulnsUrl != null)
                ? new OsvClient(
                        osvBatchUrl != null ? osvBatchUrl : OsvClient.DEFAULT_BATCH,
                        osvVulnsUrl != null ? osvVulnsUrl : OsvClient.DEFAULT_VULNS)
                : new OsvClient();
        AuditReport report = new Auditor(client).audit(lock);

        System.out.println(report.renderMarkdown());

        List<AuditReport.Finding> blocking = report.filterAtLeast(threshold);
        if (!blocking.isEmpty()) {
            System.err.println("jk audit: " + blocking.size()
                    + " finding(s) at or above " + threshold + " — failing.");
            return 1;
        }
        return 0;
    }
}
