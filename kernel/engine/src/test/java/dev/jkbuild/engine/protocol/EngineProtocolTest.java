// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.plugin.protocol.Ndjson;
import org.junit.jupiter.api.Test;

class EngineProtocolTest {

    @Test
    void hello_round_trips_the_version() {
        String json = EngineProtocol.hello("1.2.3");
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.HELLO);
        assertThat(Ndjson.str(json, "version")).isEqualTo("1.2.3");
    }

    @Test
    void hello_ack_round_trips_version_pid_and_start_time() {
        String json = EngineProtocol.helloAck("1.2.3", 4321, 999_000);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.HELLO_ACK);
        assertThat(Ndjson.str(json, "version")).isEqualTo("1.2.3");
        assertThat(Ndjson.longValue(json, "pid", -1)).isEqualTo(4321);
        assertThat(Ndjson.longValue(json, "startedAt", -1)).isEqualTo(999_000);
    }

    @Test
    void ping_and_pong_are_distinct_types() {
        assertThat(EngineProtocol.typeOf(EngineProtocol.ping())).isEqualTo(EngineProtocol.PING);
        assertThat(EngineProtocol.typeOf(EngineProtocol.pong())).isEqualTo(EngineProtocol.PONG);
    }

    @Test
    void status_ack_round_trips_all_fields() {
        String json = EngineProtocol.statusAck("1.2.3", 42, 1_000, 120, 3, 18_000_000, 42_000_000, 268_435_456, -1);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.STATUS_ACK);
        assertThat(Ndjson.str(json, "version")).isEqualTo("1.2.3");
        assertThat(Ndjson.longValue(json, "pid", -1)).isEqualTo(42);
        assertThat(Ndjson.longValue(json, "startedAt", -1)).isEqualTo(1_000);
        assertThat(Ndjson.intValue(json, "idleMinutes", -99)).isEqualTo(120);
        assertThat(Ndjson.intValue(json, "activeRequests", -99)).isEqualTo(3);
        assertThat(Ndjson.longValue(json, "heapUsedBytes", -99)).isEqualTo(18_000_000);
        assertThat(Ndjson.longValue(json, "heapCommittedBytes", -99)).isEqualTo(42_000_000);
        assertThat(Ndjson.longValue(json, "heapMaxBytes", -99)).isEqualTo(268_435_456);
        assertThat(Ndjson.longValue(json, "rssBytes", -99)).isEqualTo(-1); // -1 = unobservable
    }

    @Test
    void shutdown_and_bye_are_distinct_types() {
        assertThat(EngineProtocol.typeOf(EngineProtocol.shutdown())).isEqualTo(EngineProtocol.SHUTDOWN);
        assertThat(EngineProtocol.typeOf(EngineProtocol.bye())).isEqualTo(EngineProtocol.BYE);
    }

    @Test
    void type_of_malformed_json_is_null() {
        assertThat(EngineProtocol.typeOf("not json")).isNull();
    }

    @Test
    void build_request_carries_freshen_lock() {
        String on = EngineProtocol.buildRequest(
                "/w", "/c", null, 1, null, false, false, 0, false, false, false, false, true);
        assertThat(Ndjson.bool(on, "freshenLock", false)).isTrue();
        String off = EngineProtocol.buildRequest(
                "/w", "/c", null, 1, null, false, false, 0, false, false, false, false, false);
        assertThat(Ndjson.bool(off, "freshenLock", true)).isFalse();
    }

    @Test
    void lock_request_round_trips_all_fields() {
        String json = EngineProtocol.lockRequest(
                "/work", "/cache", java.util.List.of("a", "b"), true, true, "http://repo", true, false, true);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.LOCK_REQUEST);
        assertThat(Ndjson.str(json, "entryDir")).isEqualTo("/work");
        assertThat(Ndjson.str(json, "cache")).isEqualTo("/cache");
        assertThat(Ndjson.strArray(json, "features")).containsExactly("a", "b");
        assertThat(Ndjson.bool(json, "noDefaultFeatures", false)).isTrue();
        assertThat(Ndjson.bool(json, "sources", false)).isTrue();
        assertThat(Ndjson.str(json, "repoUrl")).isEqualTo("http://repo");
        assertThat(Ndjson.bool(json, "offline", false)).isTrue();
        assertThat(Ndjson.bool(json, "force", true)).isFalse();
        assertThat(Ndjson.bool(json, "verbose", false)).isTrue();
    }

    @Test
    void lock_request_null_repo_url_decodes_as_absent() {
        String json = EngineProtocol.lockRequest(
                "/w", "/c", java.util.List.of(), false, false, null, false, false, false);
        assertThat(Ndjson.str(json, "repoUrl")).isNull();
    }

    @Test
    void update_request_round_trips_the_git_splice_fields() {
        String json = EngineProtocol.updateRequest(
                "/work", "/cache", java.util.List.of(), false, null, true, "mylib", false, true, false);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.UPDATE_REQUEST);
        assertThat(Ndjson.bool(json, "gitOnly", false)).isTrue();
        assertThat(Ndjson.str(json, "gitTarget")).isEqualTo("mylib");
        assertThat(Ndjson.bool(json, "force", false)).isTrue();
    }

    @Test
    void sync_request_round_trips_all_fields() {
        String json = EngineProtocol.syncRequest(
                "/work", "/cache", "/jdks", "http://repo", true, false, true, true, false);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.SYNC_REQUEST);
        assertThat(Ndjson.str(json, "jdksDir")).isEqualTo("/jdks");
        assertThat(Ndjson.str(json, "repoUrl")).isEqualTo("http://repo");
        assertThat(Ndjson.bool(json, "sources", false)).isTrue();
        assertThat(Ndjson.bool(json, "offline", true)).isFalse();
        assertThat(Ndjson.bool(json, "force", false)).isTrue();
        assertThat(Ndjson.bool(json, "refresh", false)).isTrue();
    }

    @Test
    void lock_module_and_package_events_round_trip() {
        String module = EngineProtocol.lockModule("/work/api", "com.example:api");
        assertThat(EngineProtocol.typeOf(module)).isEqualTo(EngineProtocol.LOCK_MODULE);
        assertThat(Ndjson.str(module, "dir")).isEqualTo("/work/api");
        assertThat(Ndjson.str(module, "coord")).isEqualTo("com.example:api");

        String pkg = EngineProtocol.lockPackage("/work/api", "com.foo:leaf", "1.0");
        assertThat(EngineProtocol.typeOf(pkg)).isEqualTo(EngineProtocol.LOCK_PACKAGE);
        assertThat(Ndjson.str(pkg, "name")).isEqualTo("com.foo:leaf");
        assertThat(Ndjson.str(pkg, "version")).isEqualTo("1.0");
    }

    @Test
    void goal_finish_lock_variant_carries_the_lockfile_counts() {
        String json = EngineProtocol.goalFinishLock("", true, 13, 2, 1);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.GOAL_FINISH);
        assertThat(Ndjson.bool(json, "success", false)).isTrue();
        assertThat(Ndjson.longValue(json, "lockPackages", -1)).isEqualTo(13);
        assertThat(Ndjson.longValue(json, "lockSources", -1)).isEqualTo(2);
        assertThat(Ndjson.longValue(json, "lockPlugins", -1)).isEqualTo(1);
    }

    @Test
    void goal_finish_sync_variant_carries_the_summary_counts() {
        String json = EngineProtocol.goalFinishSync("", true, 7, 42);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.GOAL_FINISH);
        assertThat(Ndjson.longValue(json, "syncFetched", -1)).isEqualTo(7);
        assertThat(Ndjson.longValue(json, "syncUpToDate", -1)).isEqualTo(42);
    }

    @Test
    void lock_finish_round_trips_outcome_errors_and_refreshed_count() {
        String json = EngineProtocol.lockFinish(false, 6, java.util.List.of("boom", "again"), 3);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.LOCK_FINISH);
        assertThat(Ndjson.bool(json, "success", true)).isFalse();
        assertThat(Ndjson.intValue(json, "exitCode", -1)).isEqualTo(6);
        assertThat(Ndjson.strArray(json, "errors")).containsExactly("boom", "again");
        assertThat(Ndjson.intValue(json, "refreshed", -1)).isEqualTo(3);
    }

    // ---- Wave 2: hosted worker verbs ------------------------------------------------------------

    @Test
    void audit_request_round_trips_all_fields() {
        String json = EngineProtocol.auditRequest("/work", "/cache", "HIGH", "http://osv/batch", "http://osv/vulns/");
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.AUDIT_REQUEST);
        assertThat(Ndjson.str(json, "entryDir")).isEqualTo("/work");
        assertThat(Ndjson.str(json, "cache")).isEqualTo("/cache");
        assertThat(Ndjson.str(json, "severity")).isEqualTo("HIGH");
        assertThat(Ndjson.str(json, "osvBatchUrl")).isEqualTo("http://osv/batch");
        assertThat(Ndjson.str(json, "osvVulnsUrl")).isEqualTo("http://osv/vulns/");
        // null overrides decode as absent (the real OSV endpoints)
        assertThat(Ndjson.str(EngineProtocol.auditRequest("/w", "/c", "LOW", null, null), "osvBatchUrl"))
                .isNull();
    }

    @Test
    void audit_finding_event_round_trips_the_worker_fields() {
        String json = EngineProtocol.auditFinding("", "com.foo:leaf", "1.0", "GHSA-x", "HIGH", "bad news");
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.AUDIT_FINDING);
        assertThat(Ndjson.str(json, "module")).isEqualTo("com.foo:leaf");
        assertThat(Ndjson.str(json, "version")).isEqualTo("1.0");
        assertThat(Ndjson.str(json, "vulnId")).isEqualTo("GHSA-x");
        assertThat(Ndjson.str(json, "severity")).isEqualTo("HIGH");
        assertThat(Ndjson.str(json, "summary")).isEqualTo("bad news");
    }

    @Test
    void format_request_round_trips_the_resolved_styles() {
        String json = EngineProtocol.formatRequest(
                "/work", "/cache", true, "palantir", "kotlinlang", false, "/rw.yml", true, false);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.FORMAT_REQUEST);
        assertThat(Ndjson.bool(json, "check", false)).isTrue();
        assertThat(Ndjson.str(json, "javaStyle")).isEqualTo("palantir");
        assertThat(Ndjson.str(json, "kotlinStyle")).isEqualTo("kotlinlang");
        assertThat(Ndjson.bool(json, "optimizeImports", true)).isFalse();
        assertThat(Ndjson.str(json, "rewriteConfig")).isEqualTo("/rw.yml");
        assertThat(Ndjson.bool(json, "offline", false)).isTrue();
    }

    @Test
    void format_file_event_and_finish_variant_round_trip() {
        String file = EngineProtocol.formatFile("", "/src/A.java", "changed", null, 3, 12);
        assertThat(EngineProtocol.typeOf(file)).isEqualTo(EngineProtocol.FORMAT_FILE);
        assertThat(Ndjson.str(file, "path")).isEqualTo("/src/A.java");
        assertThat(Ndjson.str(file, "status")).isEqualTo("changed");
        assertThat(Ndjson.intValue(file, "index", -1)).isEqualTo(3);
        assertThat(Ndjson.intValue(file, "total", -1)).isEqualTo(12);

        String finish = EngineProtocol.goalFinishFormat("", true, 2, 9, 1, 12, 1);
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.GOAL_FINISH);
        assertThat(Ndjson.intValue(finish, "formatChanged", -1)).isEqualTo(2);
        assertThat(Ndjson.intValue(finish, "formatClean", -1)).isEqualTo(9);
        assertThat(Ndjson.intValue(finish, "formatErrors", -1)).isEqualTo(1);
        assertThat(Ndjson.intValue(finish, "formatTotal", -1)).isEqualTo(12);
        assertThat(Ndjson.intValue(finish, "formatWorkerExit", -1)).isEqualTo(1);
    }

    @Test
    void publish_request_round_trips_the_credential_fields() {
        String json = EngineProtocol.publishRequest(
                "/work", "/cache", "https://repo/m2", "us-east-1", null, "/out/app.jar", true, false, "/key.asc",
                "s3cret", false, true, true, "basic", "alice", "hunter2", null, false);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.PUBLISH_REQUEST);
        assertThat(Ndjson.str(json, "repoUrl")).isEqualTo("https://repo/m2");
        assertThat(Ndjson.str(json, "region")).isEqualTo("us-east-1");
        assertThat(Ndjson.str(json, "endpoint")).isNull();
        assertThat(Ndjson.str(json, "jar")).isEqualTo("/out/app.jar");
        assertThat(Ndjson.bool(json, "allowSnapshot", false)).isTrue();
        assertThat(Ndjson.str(json, "keyFile")).isEqualTo("/key.asc");
        assertThat(Ndjson.str(json, "gpgPassphrase")).isEqualTo("s3cret");
        assertThat(Ndjson.bool(json, "slsa", false)).isTrue();
        assertThat(Ndjson.bool(json, "sbom", false)).isTrue();
        assertThat(Ndjson.str(json, "authType")).isEqualTo("basic");
        assertThat(Ndjson.str(json, "user")).isEqualTo("alice");
        assertThat(Ndjson.str(json, "pass")).isEqualTo("hunter2");
        assertThat(Ndjson.str(json, "token")).isNull();

        String finish = EngineProtocol.goalFinishPublish("", true, 9);
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.GOAL_FINISH);
        assertThat(Ndjson.intValue(finish, "publishFiles", -1)).isEqualTo(9);
    }

    @Test
    void image_request_keeps_the_tarball_tristate() {
        String none = EngineProtocol.imageRequest(
                "/w", "/c", null, "com.example.Main", null, null, null, null, false, false, false, false, false);
        assertThat(EngineProtocol.typeOf(none)).isEqualTo(EngineProtocol.IMAGE_REQUEST);
        assertThat(Ndjson.str(none, "tarball")).isNull();
        String defaulted = EngineProtocol.imageRequest(
                "/w", "/c", null, null, null, null, "", null, false, false, false, false, false);
        assertThat(Ndjson.str(defaulted, "tarball")).isEmpty();
        String explicit = EngineProtocol.imageRequest(
                "/w", "/c", null, null, "reg.io", "v2", "/out/img.tar", "podman", true, true, true, true, true);
        assertThat(Ndjson.str(explicit, "tarball")).isEqualTo("/out/img.tar");
        assertThat(Ndjson.str(explicit, "registry")).isEqualTo("reg.io");
        assertThat(Ndjson.str(explicit, "dockerExecutable")).isEqualTo("podman");
        assertThat(Ndjson.bool(explicit, "skipTests", false)).isTrue();
        assertThat(Ndjson.bool(explicit, "rerun", false)).isTrue();
    }

    @Test
    void goal_finish_image_variant_carries_the_success_tail_fields_and_test_counts() {
        String json = EngineProtocol.goalFinishImage(
                "", true, 12, 12, 0, 0, "reg.io/app:1.0", null, "app", "1.0", null);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.GOAL_FINISH);
        assertThat(Ndjson.longValue(json, "testTotal", -1)).isEqualTo(12);
        assertThat(Ndjson.str(json, "imageRef")).isEqualTo("reg.io/app:1.0");
        assertThat(Ndjson.str(json, "imageTarball")).isNull();
        assertThat(Ndjson.str(json, "imageName")).isEqualTo("app");
        assertThat(Ndjson.str(json, "imageVersion")).isEqualTo("1.0");
        assertThat(Ndjson.str(json, "imageDaemonExe")).isNull();
    }

    @Test
    void import_request_note_and_finish_variant_round_trip() {
        String req = EngineProtocol.importRequest("/p/pom.xml", "/p/jk.toml", "/p", "/tmp/jk", true, null, "/cache");
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.IMPORT_REQUEST);
        assertThat(Ndjson.str(req, "source")).isEqualTo("/p/pom.xml");
        assertThat(Ndjson.str(req, "out")).isEqualTo("/p/jk.toml");
        assertThat(Ndjson.bool(req, "force", false)).isTrue();
        assertThat(Ndjson.str(req, "report")).isNull();

        String note = EngineProtocol.importNote("", "wrote", "/p/jk.toml");
        assertThat(EngineProtocol.typeOf(note)).isEqualTo(EngineProtocol.IMPORT_NOTE);
        assertThat(Ndjson.str(note, "kind")).isEqualTo("wrote");
        assertThat(Ndjson.str(note, "text")).isEqualTo("/p/jk.toml");

        String finish = EngineProtocol.goalFinishImport("", true, 0, 3, null, null);
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.GOAL_FINISH);
        assertThat(Ndjson.intValue(finish, "importExit", -1)).isEqualTo(0);
        assertThat(Ndjson.intValue(finish, "importWarnings", -1)).isEqualTo(3);
        assertThat(Ndjson.str(finish, "importError")).isNull();
    }

    @Test
    void provision_request_and_result_round_trip() {
        String req = EngineProtocol.provisionRequest("/cache", "/proj", "/cache/tools", true, true);
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.PROVISION_REQUEST);
        assertThat(Ndjson.str(req, "projectDir")).isEqualTo("/proj");
        assertThat(Ndjson.str(req, "toolsRoot")).isEqualTo("/cache/tools");
        assertThat(Ndjson.bool(req, "noDiscover", false)).isTrue();
        assertThat(Ndjson.bool(req, "gradle", false)).isTrue();

        String result = EngineProtocol.provisionResult("/cache/tools/mvn/bin/mvn", "3.9.9", "DOWNLOADED", null, 0, null);
        assertThat(EngineProtocol.typeOf(result)).isEqualTo(EngineProtocol.PROVISION_RESULT);
        assertThat(Ndjson.str(result, "bin")).isEqualTo("/cache/tools/mvn/bin/mvn");
        assertThat(Ndjson.str(result, "version")).isEqualTo("3.9.9");
        assertThat(Ndjson.str(result, "source")).isEqualTo("DOWNLOADED");
        assertThat(Ndjson.str(result, "error")).isNull();
        assertThat(Ndjson.intValue(result, "exit", -1)).isEqualTo(0);
    }
}
