// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.audit;

import dev.jkbuild.lock.Lockfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates {@code jk audit} (PRD §23.5): turn a {@link Lockfile} into
 * a list of {@link OsvClient.Query}, batch-query OSV, then fetch full
 * vuln details for any hits to populate severity + summary.
 */
public final class Auditor {

    private final OsvClient client;

    public Auditor() { this(new OsvClient()); }

    public Auditor(OsvClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public AuditReport audit(Lockfile lock) throws IOException, InterruptedException {
        List<OsvClient.Query> queries = new ArrayList<>();
        List<Lockfile.Package> pkgs = new ArrayList<>();
        for (Lockfile.Package pkg : lock.packages()) {
            queries.add(new OsvClient.Query("Maven", pkg.name(), pkg.version()));
            pkgs.add(pkg);
        }
        if (queries.isEmpty()) return new AuditReport(List.of());

        List<OsvClient.Result> results = client.queryBatch(queries);
        List<AuditReport.Finding> findings = new ArrayList<>();
        for (int i = 0; i < pkgs.size(); i++) {
            Lockfile.Package pkg = pkgs.get(i);
            for (String vulnId : results.get(i).vulnIds()) {
                OsvClient.Vulnerability v = client.fetchVuln(vulnId);
                findings.add(new AuditReport.Finding(
                        pkg.name(), pkg.version(),
                        vulnId, v.summary(),
                        AuditReport.Severity.parse(v.severity())));
            }
        }
        return new AuditReport(findings);
    }
}
