// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.audit;

import com.sun.net.httpserver.HttpServer;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.model.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditorTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> get = new HashMap<>();
    private final Map<String, byte[]> post = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if ("GET".equals(exchange.getRequestMethod())) {
                body = get.get(path);
            } else {
                body = post.get(path);
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void empty_lockfile_yields_empty_report() throws Exception {
        Lockfile lock = Lockfile.empty("test");
        AuditReport report = new Auditor(osvClient()).audit(lock);
        assertThat(report.isEmpty()).isTrue();
    }

    @Test
    void osv_findings_become_severity_classified_report() throws Exception {
        Lockfile lock = new Lockfile(5, "jk test", "pubgrub-v1", List.of(
                new Lockfile.Package("com.fasterxml.jackson.core:jackson-databind", "2.18.0",
                        "central+https://...", "sha256:abc", null,
                        List.of(Scope.MAIN), List.of()),
                new Lockfile.Package("com.example:safe", "1.0.0",
                        "central+https://...", "sha256:def", null,
                        List.of(Scope.MAIN), List.of())));

        post.put("/v1/querybatch", ("""
                {"results":[
                  {"vulns":[{"id":"GHSA-aaaa-bbbb-cccc"}]},
                  {}
                ]}
                """).getBytes(StandardCharsets.UTF_8));
        get.put("/v1/vulns/GHSA-aaaa-bbbb-cccc", ("""
                {
                  "id":"GHSA-aaaa-bbbb-cccc",
                  "summary":"Deserialization gadget",
                  "database_specific":{"severity":"HIGH"}
                }
                """).getBytes(StandardCharsets.UTF_8));

        AuditReport report = new Auditor(osvClient()).audit(lock);
        assertThat(report.findings()).hasSize(1);
        AuditReport.Finding f = report.findings().getFirst();
        assertThat(f.module()).isEqualTo("com.fasterxml.jackson.core:jackson-databind");
        assertThat(f.version()).isEqualTo("2.18.0");
        assertThat(f.vulnId()).isEqualTo("GHSA-aaaa-bbbb-cccc");
        assertThat(f.severity()).isEqualTo(AuditReport.Severity.HIGH);
        assertThat(f.summary()).contains("Deserialization");

        String md = report.renderMarkdown();
        assertThat(md).contains("**HIGH**").contains("GHSA-aaaa-bbbb-cccc");
    }

    @Test
    void severity_threshold_filter_blocks_only_at_or_above() throws Exception {
        Lockfile lock = new Lockfile(5, "jk test", "pubgrub-v1", List.of(
                new Lockfile.Package("g:a", "1.0", "s", "sha256:x", null,
                        List.of(Scope.MAIN), List.of()),
                new Lockfile.Package("g:b", "1.0", "s", "sha256:y", null,
                        List.of(Scope.MAIN), List.of())));
        post.put("/v1/querybatch", ("""
                {"results":[
                  {"vulns":[{"id":"A"}]},
                  {"vulns":[{"id":"B"}]}
                ]}
                """).getBytes(StandardCharsets.UTF_8));
        get.put("/v1/vulns/A", ("""
                {"id":"A","summary":"low","database_specific":{"severity":"LOW"}}
                """).getBytes(StandardCharsets.UTF_8));
        get.put("/v1/vulns/B", ("""
                {"id":"B","summary":"high","database_specific":{"severity":"HIGH"}}
                """).getBytes(StandardCharsets.UTF_8));

        AuditReport report = new Auditor(osvClient()).audit(lock);
        assertThat(report.filterAtLeast(AuditReport.Severity.HIGH))
                .extracting(AuditReport.Finding::vulnId).containsExactly("B");
        assertThat(report.filterAtLeast(AuditReport.Severity.LOW))
                .extracting(AuditReport.Finding::vulnId).containsExactlyInAnyOrder("A", "B");
    }

    private OsvClient osvClient() {
        return new OsvClient(base.resolve("/v1/querybatch"), base.resolve("/v1/vulns/"));
    }
}
