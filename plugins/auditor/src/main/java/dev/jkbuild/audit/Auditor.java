// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.audit;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.run.JkThreads;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Orchestrates {@code jk audit} (PRD §23.5): turn a {@link Lockfile} into a list of {@link
 * OsvClient.Query}, batch-query OSV, then fetch full vuln details for any hits to populate severity
 * + summary.
 *
 * <p>Per-vuln fetches dispatch concurrently on {@link JkThreads#io()} — each is an independent
 * {@code GET /v1/vulns/<id>} HTTP call. Findings are emitted in deterministic order (package
 * iteration order × OSV result order) regardless of completion order.
 */
public final class Auditor {

    private final OsvClient client;

    public Auditor() {
        this(new OsvClient());
    }

    public Auditor(OsvClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public AuditReport audit(Lockfile lock) throws IOException, InterruptedException {
        List<OsvClient.Query> queries = new ArrayList<>();
        List<Lockfile.Artifact> pkgs = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            queries.add(new OsvClient.Query("Maven", pkg.name(), pkg.version()));
            pkgs.add(pkg);
        }
        if (queries.isEmpty()) return new AuditReport(List.of());

        List<OsvClient.Result> results = client.queryBatch(queries);

        // Collect every unique vuln ID surfaced by the batch — a single CVE
        // can apply to multiple packages, and we don't want to fetch its
        // details N times.
        Set<String> uniqueIds = new LinkedHashSet<>();
        for (OsvClient.Result r : results) uniqueIds.addAll(r.vulnIds());
        if (uniqueIds.isEmpty()) return new AuditReport(List.of());

        // Dispatch all detail fetches concurrently.
        Map<String, CompletableFuture<OsvClient.Vulnerability>> futures = uniqueIds.stream()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return client.fetchVuln(id);
                                    } catch (IOException | InterruptedException e) {
                                        if (e instanceof InterruptedException)
                                            Thread.currentThread().interrupt();
                                        throw new RuntimeException(e);
                                    }
                                },
                                JkThreads.io())));

        // Assemble findings in the same package × vulnId order as before —
        // determinism is important for the supply-chain report sidecar.
        List<AuditReport.Finding> findings = new ArrayList<>();
        try {
            for (int i = 0; i < pkgs.size(); i++) {
                Lockfile.Artifact pkg = pkgs.get(i);
                for (String vulnId : results.get(i).vulnIds()) {
                    OsvClient.Vulnerability v = futures.get(vulnId).get();
                    findings.add(new AuditReport.Finding(
                            pkg.name(), pkg.version(), vulnId, v.summary(), AuditReport.Severity.parse(v.severity())));
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re && re.getCause() instanceof IOException io) throw io;
            if (cause instanceof RuntimeException re && re.getCause() instanceof InterruptedException ie) throw ie;
            throw new IOException("audit vuln-detail fetch failed: " + cause.getMessage(), cause);
        }
        return new AuditReport(findings);
    }
}
