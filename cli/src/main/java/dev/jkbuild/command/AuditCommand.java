// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.audit.AuditReport;
import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.worker.WorkerProcess;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.util.JkDirs;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk audit} — scan the lockfile against OSV (PRD §23.5). Exits
 * non-zero when any finding at or above the configured severity threshold
 * is present.
 *
 * <p>The OSV query runs in the {@code jk-audit-runner} worker subprocess so
 * Jackson and the OSV HTTP client are isolated from the main jk binary.
 *
 * <p>Three phases: {@code read-lock} (SYNC) validates jk.lock exists;
 * {@code query-osv} (IO) forks the worker and streams NDJSON findings back;
 * {@code evaluate} (SYNC) applies the severity threshold.
 */
public final class AuditCommand implements CliCommand {

    @Override public String name() { return "audit"; }
    @Override public String description() { return "Check the locked dependencies for known vulnerabilities"; }
    @Override public java.util.List<Opt> options() {
        return java.util.List.of(
                Opt.value("<level>", "Fail when any finding is at least this severe. One of CRITICAL, HIGH, MEDIUM, LOW. Default: LOW.", "--severity"),
                Opt.value("<url>", "Override the OSV batch query URL (for tests).", "--osv-batch-url").hide(),
                Opt.value("<url>", "Override the OSV vulnerability lookup URL (for tests).", "--osv-vulns-url").hide());
    }
    URI osvBatchUrl;
    URI osvVulnsUrl;

    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> FINDINGS = GoalKey.of("findings", List.class);

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        GlobalOptions global = GlobalOptions.from(in);
        this.osvBatchUrl = in.value("osv-batch-url").map(java.net.URI::create).orElse(null);
        this.osvVulnsUrl = in.value("osv-vulns-url").map(java.net.URI::create).orElse(null);
        String severity = in.value("severity").orElse("LOW");
        Path projectDir = global.workingDir();
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(lockPath)) {
            System.err.println("jk audit: no jk.lock in " + dev.jkbuild.cli.PathDisplay.styledRaw(projectDir)
                    + " (run `jk lock` first).");
            return 2;
        }
        if (global.offline) {
            System.err.println("jk audit: --offline is set; OSV queries require network access.");
            return 1;
        }
        Path cache = JkDirs.cache();
        AuditReport.Severity threshold = AuditReport.Severity.parse(severity);

        Phase readLock = Phase.builder("read-lock")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("read jk.lock");
                    // Validates the lockfile is readable; the worker re-reads it.
                    LockfileReader.read(lockPath);
                    ctx.progress(1);
                })
                .build();

        Phase queryOsv = Phase.builder("query-osv")
                .kind(PhaseKind.IO)
                .requires("read-lock")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("query OSV via audit worker");
                    try {
                        List<AuditReport.Finding> findings = runWorker(lockPath, cache);
                        ctx.put(FINDINGS, findings);
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

        @SuppressWarnings("unchecked")
        List<AuditReport.Finding> findings =
                (List<AuditReport.Finding>) goal.get(FINDINGS).orElse(List.of());
        AuditReport report = new AuditReport(findings);

        if (!global.outputIsJson()) {
            System.out.println(report.renderMarkdown());
        }

        List<AuditReport.Finding> blocking = report.filterAtLeast(threshold);
        if (!blocking.isEmpty()) {
            System.err.println("jk audit: " + blocking.size()
                    + " finding(s) at or above " + threshold + " — failing.");
            return 1;
        }
        return 0;
    }

    /**
     * Fork the {@code jk-audit-runner} worker and collect its NDJSON
     * findings. Uses a hand-rolled field extractor — no Jackson required in
     * the driver.
     */
    private List<AuditReport.Finding> runWorker(Path lockPath, Path cache)
            throws IOException, InterruptedException {
        Path workerJar = WorkerJar.AUDITOR.locate(new Cas(cache));
        Path spec = writeSpec(lockPath);
        try {
            Path javaExe = CompileToolchain.runningJavaHome()
                    .resolve("bin")
                    .resolve(isWindows() ? "java.exe" : "java");
            List<String> cmd = dev.jkbuild.worker.JvmOptions.javaCommand(javaExe.toString(), 1,
                    List.of("-jar", workerJar.toString(), spec.toAbsolutePath().toString()));

            List<AuditReport.Finding> findings = new ArrayList<>();
            int exit = WorkerProcess.run(cmd, "##JKAU:", json -> {
                if (!"finding".equals(Ndjson.str(json, "t"))) return;
                String module   = Ndjson.str(json, "module");
                String version  = Ndjson.str(json, "version");
                String vulnId   = Ndjson.str(json, "vuln_id");
                String severity = Ndjson.str(json, "severity");
                String summary  = Ndjson.str(json, "summary");
                if (module != null && version != null && vulnId != null) {
                    findings.add(new AuditReport.Finding(
                            module, version, vulnId,
                            summary != null ? summary : "",
                            AuditReport.Severity.parse(severity)));
                }
            }, null);
            if (exit != 0) {
                throw new RuntimeException("audit worker exited with code " + exit);
            }
            return findings;
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private Path writeSpec(Path lockPath) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("LOCKFILE " + lockPath.toAbsolutePath());
        if (osvBatchUrl != null) lines.add("BATCH_URL " + osvBatchUrl);
        if (osvVulnsUrl != null) lines.add("VULNS_URL " + osvVulnsUrl);
        Path spec = Files.createTempFile("jk-audit-", ".spec");
        Files.write(spec, lines, StandardCharsets.UTF_8);
        return spec;
    }

    /**
     * Extract a JSON string field value from a simple NDJSON object without a
     * full parser. Handles basic backslash escapes but not surrogate pairs.
     */

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
