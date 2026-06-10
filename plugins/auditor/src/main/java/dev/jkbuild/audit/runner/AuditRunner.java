// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.audit.runner;

import dev.jkbuild.audit.AuditReport;
import dev.jkbuild.audit.Auditor;
import dev.jkbuild.audit.OsvClient;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.plugin.Plugin;
import dev.jkbuild.plugin.PluginManifest;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.plugin.protocol.ProtocolWriter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code jk-audit-runner} plugin: queries the OSV vulnerability API in an
 * isolated child JVM so Jackson and the OSV HTTP client never load in jk's own
 * process. Discovered by the shared {@link dev.jkbuild.plugin.host.PluginHostMain}
 * via {@link java.util.ServiceLoader}.
 *
 * <p>Receives a single argument: the path to a line-oriented spec file:
 * <pre>
 * LOCKFILE /absolute/path/to/jk.lock
 * BATCH_URL https://api.osv.dev/v1/querybatch  # optional override
 * VULNS_URL https://api.osv.dev/v1/vulns/       # optional override
 * </pre>
 *
 * <p>Streams NDJSON to stdout, each line prefixed {@code ##JKAU:}:
 * <pre>
 * ##JKAU:{"t":"finding","module":"g:a","version":"1.0","vuln_id":"GHSA-xx","severity":"HIGH","summary":"..."}
 * ##JKAU:{"t":"result","total":1}
 * </pre>
 *
 * <p>Exit codes: 0 success (findings may still be present; threshold is the
 * caller's decision), 1 network/parse error, 2 bad arguments.
 */
public final class AuditRunner implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-auditor", "##JKAU:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        if (args.isEmpty()) {
            System.err.println("jk-audit-runner: expected spec file path as first argument");
            return 2;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("jk-audit-runner: spec file not found: " + specFile);
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
                    default -> { }
                }
            }
        } catch (IOException e) {
            System.err.println("jk-audit-runner: could not read spec file: " + e.getMessage());
            return 2;
        }
        if (lockfilePath == null) {
            System.err.println("jk-audit-runner: spec file missing LOCKFILE line");
            return 2;
        }

        Lockfile lock;
        try {
            lock = LockfileReader.read(lockfilePath);
        } catch (IOException e) {
            System.err.println("jk-audit-runner: could not read lockfile: " + e.getMessage());
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
            System.err.println("jk-audit-runner: OSV query failed: " + e.getMessage());
            return 1;
        }

        List<AuditReport.Finding> findings = report.findings();
        for (AuditReport.Finding f : findings) {
            out.emit("{"
                    + "\"t\":\"finding\","
                    + "\"module\":" + Ndjson.quote(f.module()) + ","
                    + "\"version\":" + Ndjson.quote(f.version()) + ","
                    + "\"vuln_id\":" + Ndjson.quote(f.vulnId()) + ","
                    + "\"severity\":" + Ndjson.quote(f.severity().name()) + ","
                    + "\"summary\":" + Ndjson.quote(f.summary())
                    + "}");
        }
        out.emit("{\"t\":\"result\",\"total\":" + findings.size() + "}");
        return 0;
    }
}
