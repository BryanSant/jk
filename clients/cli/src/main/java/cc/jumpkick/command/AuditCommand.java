// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.runtime.HostedEvents;
import cc.jumpkick.util.JkDirs;
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
 * resident engine ({@link cc.jumpkick.cli.engine.EngineClient#runAudit}); findings stream back as
 * structured events and this command assembles/renders the report and applies the threshold. The
 * pipeline machinery lives in the engine's {@code AuditPipelines} so the test-only in-process path (see
 * {@link #engineDisabledForTests}) builds the identical pipeline.
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
                || "cc.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
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
                    + cc.jumpkick.cli.PathDisplay.styledRaw(projectDir)
                    + " (run `jk lock` first).");
            return Exit.CONFIG;
        }
        if (global.offline) {
            CliOutput.err("jk audit: --offline is set; OSV queries require network access.");
            return 1;
        }
        Path cache = JkDirs.cache();
        AuditReport.Severity threshold = AuditReport.Severity.parse(severity);
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);

        // Findings accumulate here from either transport — raw worker fields in, typed report rows
        // out — so the report/threshold tail below is transport-agnostic.
        List<AuditReport.Finding> findings = new ArrayList<>();
        HostedEvents.FindingObserver observer = (module, version, vulnId, sev, summary) -> {
            if (module != null && version != null && vulnId != null) {
                findings.add(new AuditReport.Finding(
                        module, version, vulnId, summary != null ? summary : "", AuditReport.Severity.parse(sev)));
            }
        };

        PipelineResult result;
        if (engineDisabledForTests()) {
            result = cc.jumpkick.cli.engine.InProcessEngine.require()
                    .auditPipeline(lockPath, cache, threshold.toString(), osvBatchUrl, osvVulnsUrl, observer, mode);
        } else {
            try {
                result = cc.jumpkick.cli.engine.EngineClient.runAudit(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineClient.AuditRequest(
                                projectDir, cache, threshold.toString(), osvBatchUrl, osvVulnsUrl),
                        steps -> PipelineConsole.chooseConsoleListener("audit", steps, mode),
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
