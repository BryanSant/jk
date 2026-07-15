// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.audit;

import build.jumpkick.audit.AuditReport;
import build.jumpkick.audit.Auditor;
import build.jumpkick.audit.OsvClient;
import build.jumpkick.lock.Lockfile;
import build.jumpkick.lock.LockfileReader;
import build.jumpkick.plugin.Plugin;
import build.jumpkick.plugin.PluginConfig;
import build.jumpkick.plugin.PluginManifest;
import build.jumpkick.plugin.protocol.ProtocolWriter;
import build.jumpkick.plugin.protocol.PluginReply;
import build.jumpkick.plugin.protocol.PluginSpec;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The {@code jk-auditor} worker (op {@code command}/{@code audit}): queries the OSV vulnerability
 * API in an isolated child JVM so Jackson and the OSV HTTP client never load in jk's own process.
 * Speaks the unified worker wire — the spec is NDJSON ({@code config}: {@code lockfile},
 * {@code batchUrl}?, {@code vulnsUrl}?) and the reply is {@code finding} lines + a terminal
 * {@code done}. Exit 0 success, 1 network/parse error, 2 bad arguments.
 */
public final class AuditPlugin implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-auditor", "##JKAU:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        if (args.isEmpty()) {
            System.err.println("jk-auditor: expected spec file path as first argument");
            return 2;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("jk-auditor: spec file not found: " + specFile);
            return 2;
        }
        PluginSpec spec;
        try {
            spec = PluginSpec.read(specFile);
        } catch (IOException e) {
            System.err.println("jk-auditor: could not read spec file: " + e.getMessage());
            return 2;
        }
        PluginConfig config = spec.config();
        Optional<String> lockfile = config.stringOpt("lockfile");
        if (lockfile.isEmpty()) {
            System.err.println("jk-auditor: spec missing `lockfile` config");
            return 2;
        }

        URI batchUrl = config.stringOpt("batchUrl").map(URI::create).orElse(null);
        URI vulnsUrl = config.stringOpt("vulnsUrl").map(URI::create).orElse(null);

        Lockfile lock;
        try {
            lock = LockfileReader.read(Path.of(lockfile.get()));
        } catch (IOException e) {
            out.emit(PluginReply.error("lockfile", e.getMessage()));
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
            out.emit(PluginReply.error("osv", e.getMessage()));
            return 1;
        }

        for (AuditReport.Finding f : report.findings()) {
            out.emit(PluginReply.finding(f.module(), f.version(), f.vulnId(), f.severity().name(), f.summary()));
        }
        out.emit(PluginReply.done(0));
        return 0;
    }
}
