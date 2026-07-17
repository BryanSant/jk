// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepKind;
import cc.jumpkick.run.StepNames;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The shared {@code jk audit} pipeline — scan {@code jk.lock} against OSV via the {@code jk-auditor}
 * plugin — hoisted out of the CLI so the resident engine can host the command (Wave 2 of {@code
 * docs/architecture/slim-client.md}) while the command's test-only in-process path builds the exact
 * same pipeline. Three steps: {@code read-lock} (SYNC) validates the lockfile is readable, {@code
 * query-osv} (IO) forks the plugin and streams its JSONL findings to the {@link FindingObserver},
 * {@code evaluate} (SYNC) is the threshold bookkeeping marker (the threshold itself is applied by
 * the client, which owns the report rendering and the exit code).
 *
 * <p>Findings cross the observer as plain structured strings exactly as the plugin reported them —
 * no theming, no report assembly — so the same observer contract serves the wire ({@code
 * audit-finding} events) and the in-process accumulation.
 */
public final class AuditPipelines {

    private AuditPipelines() {}

    /** Receives each finding as the plugin streams it (raw plugin fields; any may be {@code null}). */
    public interface FindingObserver {
        void onFinding(String module, String version, String vulnId, String severity, String summary);
    }

    /**
     * Build the audit pipeline for {@code lockPath}. Locates the plugin jar eagerly, so a missing plugin
     * fails here (with {@link cc.jumpkick.engine.plugin.PluginJarNotFoundException}'s side-load
     * instructions) rather than mid-pipeline. {@code thresholdLabel} only feeds the evaluate step's
     * label; {@code osvBatchUrl}/{@code osvVulnsUrl} are the hidden test overrides ({@code null} =
     * the real OSV endpoints).
     */
    public static Pipeline auditPipeline(
            Path lockPath,
            Path cache,
            String thresholdLabel,
            URI osvBatchUrl,
            URI osvVulnsUrl,
            FindingObserver observer) {
        Path workerJar = PluginJar.AUDITOR.locate(new Cas(cache));

        Step readLock = Step.builder(StepNames.READ_LOCK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("read jk.lock");
                    // Validates the lockfile is readable; the plugin re-reads it.
                    LockfileReader.read(lockPath);
                    ctx.progress(1);
                })
                .build();

        Step queryOsv = Step.builder(StepNames.QUERY_OSV)
                .kind(StepKind.IO)
                .requires(StepNames.READ_LOCK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("query OSV via audit worker");
                    try {
                        runWorker(workerJar, lockPath, osvBatchUrl, osvVulnsUrl, observer);
                    } catch (RuntimeException e) {
                        ctx.error("osv", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Step evaluate = Step.builder("evaluate")
                .requires(StepNames.QUERY_OSV)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("apply " + thresholdLabel + " threshold");
                    ctx.progress(1);
                })
                .build();

        return Pipeline.builder("audit")
                .addStep(readLock)
                .addStep(queryOsv)
                .addStep(evaluate)
                .build();
    }

    /** Fork the {@code jk-auditor} plugin and stream its JSONL findings to {@code observer}. */
    private static void runWorker(
            Path workerJar, Path lockPath, URI osvBatchUrl, URI osvVulnsUrl, FindingObserver observer) {
        try {
            Path spec = writeSpec(lockPath, osvBatchUrl, osvVulnsUrl);
            try {
                String[] error = {null};
                int exit = new PluginClient("##JKAU:")
                        .on(
                                PluginProtocol.FINDING,
                                json -> observer.onFinding(
                                        Jsonl.str(json, "module"),
                                        Jsonl.str(json, "version"),
                                        Jsonl.str(json, "id"),
                                        Jsonl.str(json, "severity"),
                                        Jsonl.str(json, "summary")))
                        .on(PluginProtocol.ERROR, json -> error[0] = Jsonl.str(json, PluginProtocol.MESSAGE))
                        .run(PluginLaunch.javaCommand(workerJar, spec));
                if (error[0] != null) {
                    throw new RuntimeException("audit worker: " + error[0]);
                }
                if (exit != 0) {
                    throw new RuntimeException("audit worker exited with code " + exit);
                }
            } finally {
                Files.deleteIfExists(spec);
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("audit worker interrupted", e);
        }
    }

    private static Path writeSpec(Path lockPath, URI osvBatchUrl, URI osvVulnsUrl) throws IOException {
        SpecWriter spec = new SpecWriter()
                .op(PluginProtocol.OP_COMMAND, "audit", "jk-auditor")
                .configString("lockfile", lockPath.toAbsolutePath().toString());
        if (osvBatchUrl != null) spec.configString("batchUrl", osvBatchUrl.toString());
        if (osvVulnsUrl != null) spec.configString("vulnsUrl", osvVulnsUrl.toString());
        Path file = Files.createTempFile("jk-audit-", ".spec");
        Files.write(file, spec.lines(), StandardCharsets.UTF_8);
        return file;
    }
}
