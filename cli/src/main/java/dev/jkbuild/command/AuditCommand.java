// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.audit.AuditReport;
import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.runtime.HostedEvents;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk audit} — scan the lockfile against OSV (PRD §23.5). Exits non-zero when any finding at
 * or above the configured severity threshold is present.
 *
 * <p>The OSV query runs in the {@code jk-audit-runner} worker subprocess so Jackson and the OSV
 * HTTP client are isolated from the main jk binary.
 *
 * <p><b>Engine-hosted</b> (Wave 2 of the slim-client migration): the worker forks inside the
 * resident engine ({@link dev.jkbuild.cli.engine.EngineClient#runAudit}); findings stream back as
 * structured events and this command assembles/renders the report and applies the threshold. The
 * goal machinery lives in the engine's {@code AuditGoals} so the test-only in-process path (see
 * {@link #engineDisabledForTests}) builds the identical goal.
 */
public final class AuditCommand implements CliCommand {

    @Override
    public String name() {
        return "audit";
    }

    @Override
    public String description() {
        return "Check the locked dependencies for known vulnerabilities";
    }

    @Override
    public java.util.List<Opt> options() {
        return java.util.List.of(
                Opt.value("<level>", "Severity: CRITICAL|HIGH|MEDIUM|LOW. Default: LOW.", "--severity"),
                Opt.value("<url>", "Override the OSV batch query URL (for tests).", "--osv-batch-url")
                        .hide(),
                Opt.value("<url>", "Override the OSV vulnerability lookup URL (for tests).", "--osv-vulns-url")
                        .hide());
    }

    URI osvBatchUrl;
    URI osvVulnsUrl;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; a real {@code jk audit} invocation always engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        GlobalOptions global = GlobalOptions.from(in);
        this.osvBatchUrl = in.value("osv-batch-url").map(java.net.URI::create).orElse(null);
        this.osvVulnsUrl = in.value("osv-vulns-url").map(java.net.URI::create).orElse(null);
        String severity = in.value("severity").orElse("LOW");
        Path projectDir = global.workingDir();
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(lockPath)) {
            CliOutput.err("jk audit: no jk.lock in "
                    + dev.jkbuild.cli.PathDisplay.styledRaw(projectDir)
                    + " (run `jk lock` first).");
            return Exit.CONFIG;
        }
        if (global.offline) {
            CliOutput.err("jk audit: --offline is set; OSV queries require network access.");
            return 1;
        }
        Path cache = JkDirs.cache();
        AuditReport.Severity threshold = AuditReport.Severity.parse(severity);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);

        // Findings accumulate here from either transport — raw worker fields in, typed report rows
        // out — so the report/threshold tail below is transport-agnostic.
        List<AuditReport.Finding> findings = new ArrayList<>();
        HostedEvents.FindingObserver observer = (module, version, vulnId, sev, summary) -> {
            if (module != null && version != null && vulnId != null) {
                findings.add(new AuditReport.Finding(
                        module, version, vulnId, summary != null ? summary : "", AuditReport.Severity.parse(sev)));
            }
        };

        GoalResult result;
        if (engineDisabledForTests()) {
            result = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .auditGoal(lockPath, cache, threshold.toString(), osvBatchUrl, osvVulnsUrl, observer, mode);
        } else {
            try {
                result = dev.jkbuild.cli.engine.EngineClient.runAudit(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.AuditRequest(
                                projectDir, cache, threshold.toString(), osvBatchUrl, osvVulnsUrl),
                        phases -> GoalConsole.chooseConsoleListener("audit", phases, mode),
                        observer);
            } catch (IOException e) {
                CliOutput.err("jk audit: " + e.getMessage());
                return Exit.SOFTWARE;
            }
        }
        if (!result.success()) return 1;

        AuditReport report = new AuditReport(findings);
        if (!global.outputIsJson()) {
            CliOutput.out(report.renderMarkdown());
        }

        List<AuditReport.Finding> blocking = report.filterAtLeast(threshold);
        if (!blocking.isEmpty()) {
            CliOutput.err("jk audit: " + blocking.size() + " finding(s) at or above " + threshold + " — failing.");
            return 1;
        }
        return 0;
    }
}
