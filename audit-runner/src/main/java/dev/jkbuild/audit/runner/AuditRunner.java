// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.audit.runner;

import dev.jkbuild.audit.AuditReport;
import dev.jkbuild.audit.Auditor;
import dev.jkbuild.audit.OsvClient;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for the {@code jk-audit-runner} worker subprocess.
 *
 * <p>Receives a single argument: the path to a line-oriented spec file:
 * <pre>
 * LOCKFILE /absolute/path/to/jk.lock
 * BATCH_URL https://api.osv.dev/v1/querybatch  # optional override
 * VULNS_URL https://api.osv.dev/v1/vulns/       # optional override
 * </pre>
 *
 * <p>Streams NDJSON to stdout, each line prefixed {@value #PREFIX}:
 * <pre>
 * ##JKAU:{"t":"finding","module":"g:a","version":"1.0","vuln_id":"GHSA-xx","severity":"HIGH","summary":"..."}
 * ##JKAU:{"t":"result","total":1}
 * </pre>
 *
 * <p>Exit codes: 0 success (findings may still be present; threshold is the
 * caller's decision), 1 network/parse error, 2 bad arguments.
 */
public final class AuditRunner {

    static final String PREFIX = "##JKAU:";

    private AuditRunner() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 1) {
            err.println("jk-audit-runner: expected spec file path as first argument");
            return 2;
        }
        Path specFile = Path.of(args[0]);
        if (!Files.isRegularFile(specFile)) {
            err.println("jk-audit-runner: spec file not found: " + specFile);
            return 2;
        }

        Path lockfilePath = null;
        URI batchUrl = null;
        URI vulnsUrl = null;
        try {
            for (String line : Files.readAllLines(specFile, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int space = line.indexOf(' ');
                if (space < 0) continue;
                String key = line.substring(0, space);
                String val = line.substring(space + 1).strip();
                switch (key) {
                    case "LOCKFILE"  -> lockfilePath = Path.of(val);
                    case "BATCH_URL" -> batchUrl = URI.create(val);
                    case "VULNS_URL" -> vulnsUrl = URI.create(val);
                }
            }
        } catch (IOException e) {
            err.println("jk-audit-runner: could not read spec file: " + e.getMessage());
            return 2;
        }
        if (lockfilePath == null) {
            err.println("jk-audit-runner: spec file missing LOCKFILE line");
            return 2;
        }

        Lockfile lock;
        try {
            lock = LockfileReader.read(lockfilePath);
        } catch (IOException e) {
            err.println("jk-audit-runner: could not read lockfile: " + e.getMessage());
            return 1;
        }

        OsvClient client = (batchUrl != null || vulnsUrl != null)
                ? new OsvClient(
                        batchUrl != null ? batchUrl : OsvClient.DEFAULT_BATCH,
                        vulnsUrl != null ? vulnsUrl : OsvClient.DEFAULT_VULNS)
                : new OsvClient();

        AuditReport report;
        try {
            report = new Auditor(client).audit(lock);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            err.println("jk-audit-runner: OSV query failed: " + e.getMessage());
            return 1;
        }

        List<AuditReport.Finding> findings = report.findings();
        for (AuditReport.Finding f : findings) {
            out.println(PREFIX + "{"
                    + "\"t\":\"finding\","
                    + "\"module\":" + quote(f.module()) + ","
                    + "\"version\":" + quote(f.version()) + ","
                    + "\"vuln_id\":" + quote(f.vulnId()) + ","
                    + "\"severity\":" + quote(f.severity().name()) + ","
                    + "\"summary\":" + quote(f.summary())
                    + "}");
        }
        out.println(PREFIX + "{\"t\":\"result\",\"total\":" + findings.size() + "}");
        out.flush();
        return 0;
    }

    static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
