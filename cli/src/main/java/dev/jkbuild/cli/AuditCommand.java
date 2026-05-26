// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.audit.AuditReport;
import dev.jkbuild.audit.Auditor;
import dev.jkbuild.audit.OsvClient;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.util.JkDirs;
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
 *
 * <p>Three phases: {@code read-lock} (SYNC) reads jk.lock; {@code query-osv}
 * (IO) hits the OSV batch endpoint — this is the slow, network-bound step;
 * {@code evaluate} (SYNC) applies the severity threshold to the findings.
 */
@Command(name = "audit", description = "Check the locked dependencies for known vulnerabilities (OSV)")
public final class AuditCommand implements Callable<Integer> {

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

    @picocli.CommandLine.Mixin GlobalOptions global;

    private static final GoalKey<Lockfile> LOCK = GoalKey.of("lock", Lockfile.class);
    private static final GoalKey<AuditReport> REPORT = GoalKey.of("report", AuditReport.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> BLOCKING = GoalKey.of("blocking", List.class);

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = global.workingDir();
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(lockPath)) {
            System.err.println("jk audit: no jk.lock in " + projectDir
                    + " (run `jk lock` first).");
            return 2;
        }
        Path cache = JkDirs.cache();
        AuditReport.Severity threshold = AuditReport.Severity.parse(severity);

        Phase readLock = Phase.builder("read-lock")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("read jk.lock");
                    ctx.put(LOCK, LockfileReader.read(lockPath));
                    ctx.progress(1);
                })
                .build();

        Phase queryOsv = Phase.builder("query-osv")
                .kind(PhaseKind.IO)
                .requires("read-lock")
                .scope(1)
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCK);
                    ctx.label("query OSV (" + lock.packages().size() + " packages)");
                    OsvClient client = (osvBatchUrl != null || osvVulnsUrl != null)
                            ? new OsvClient(
                                    osvBatchUrl != null ? osvBatchUrl : OsvClient.DEFAULT_BATCH,
                                    osvVulnsUrl != null ? osvVulnsUrl : OsvClient.DEFAULT_VULNS)
                            : new OsvClient();
                    try {
                        ctx.put(REPORT, new Auditor(client).audit(lock));
                    } catch (RuntimeException e) {
                        ctx.error("osv", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Phase evaluate = Phase.builder("evaluate")
                .requires("query-osv")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("apply " + threshold + " threshold");
                    List<AuditReport.Finding> blocking =
                            ctx.require(REPORT).filterAtLeast(threshold);
                    ctx.put(BLOCKING, blocking);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("audit")
                .addPhase(readLock)
                .addPhase(queryOsv)
                .addPhase(evaluate)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) return 1;

        AuditReport report = goal.get(REPORT).orElseThrow();
        if (!global.outputIsJson()) {
            System.out.println(report.renderMarkdown());
        }

        @SuppressWarnings("unchecked")
        List<AuditReport.Finding> blocking =
                (List<AuditReport.Finding>) goal.get(BLOCKING).orElse(List.of());
        if (!blocking.isEmpty()) {
            System.err.println("jk audit: " + blocking.size()
                    + " finding(s) at or above " + threshold + " — failing.");
            return 1;
        }
        return 0;
    }
}
