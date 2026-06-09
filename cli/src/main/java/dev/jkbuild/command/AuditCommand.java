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
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

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
@Command(name = "audit", description = "Check the locked dependencies for known vulnerabilities")
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

    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> FINDINGS = GoalKey.of("findings", List.class);

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = global.workingDir();
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(lockPath)) {
            System.err.println("jk audit: no jk.lock in " + projectDir
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
        Path workerJar = WorkerJar.AUDIT_RUNNER.locate(new Cas(cache));
        Path spec = writeSpec(lockPath);
        try {
            Path javaExe = CompileToolchain.runningJavaHome()
                    .resolve("bin")
                    .resolve(isWindows() ? "java.exe" : "java");
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe.toString());
            cmd.add("-jar");
            cmd.add(workerJar.toString());
            cmd.add(spec.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process process = pb.start();

            List<AuditReport.Finding> findings = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("##JKAU:")) continue;
                    String json = line.substring("##JKAU:".length());
                    String t = readField(json, "t");
                    if ("finding".equals(t)) {
                        String module   = readField(json, "module");
                        String version  = readField(json, "version");
                        String vulnId   = readField(json, "vuln_id");
                        String severity = readField(json, "severity");
                        String summary  = readField(json, "summary");
                        if (module != null && version != null && vulnId != null) {
                            findings.add(new AuditReport.Finding(
                                    module, version, vulnId,
                                    summary != null ? summary : "",
                                    AuditReport.Severity.parse(severity)));
                        }
                    }
                }
            }
            int exit = process.waitFor();
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
    private static String readField(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default  -> { sb.append('\\'); sb.append(next); }
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
