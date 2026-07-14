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
    void hello_ack_round_trips_version_pid_start_time_and_build_id() {
        String json = EngineProtocol.helloAck("1.2.3", 4321, 999_000, true, "abc123def456");
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.HELLO_ACK);
        assertThat(Ndjson.str(json, "version")).isEqualTo("1.2.3");
        assertThat(Ndjson.longValue(json, "pid", -1)).isEqualTo(4321);
        assertThat(Ndjson.longValue(json, "startedAt", -1)).isEqualTo(999_000);
        assertThat(Ndjson.bool(json, "draining", false)).isTrue();
        assertThat(Ndjson.str(json, "buildId")).isEqualTo("abc123def456");
        // Identity-less contexts answer an EMPTY buildId ("no opinion"), never null.
        assertThat(Ndjson.str(EngineProtocol.helloAck("1.2.3", 1, 1, false, null), "buildId")).isEmpty();
    }

    @Test
    void ping_and_pong_are_distinct_types() {
        assertThat(EngineProtocol.typeOf(EngineProtocol.ping())).isEqualTo(EngineProtocol.PING);
        assertThat(EngineProtocol.typeOf(EngineProtocol.pong())).isEqualTo(EngineProtocol.PONG);
    }

    @Test
    void status_ack_round_trips_all_fields() {
        String json = EngineProtocol.statusAck(
                "1.2.3", 42, 1_000, 3, 7, true, 18_000_000, 42_000_000, 268_435_456, -1, -1, null, null);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.STATUS_ACK);
        assertThat(Ndjson.str(json, "version")).isEqualTo("1.2.3");
        assertThat(Ndjson.longValue(json, "pid", -1)).isEqualTo(42);
        assertThat(Ndjson.longValue(json, "startedAt", -1)).isEqualTo(1_000);
        assertThat(Ndjson.intValue(json, "activeRequests", -99)).isEqualTo(3);
        assertThat(Ndjson.intValue(json, "activePipelines", -99)).isEqualTo(7);
        assertThat(Ndjson.bool(json, "draining", false)).isTrue();
        assertThat(Ndjson.longValue(json, "heapUsedBytes", -99)).isEqualTo(18_000_000);
        assertThat(Ndjson.longValue(json, "heapCommittedBytes", -99)).isEqualTo(42_000_000);
        assertThat(Ndjson.longValue(json, "heapMaxBytes", -99)).isEqualTo(268_435_456);
        assertThat(Ndjson.longValue(json, "rssBytes", -99)).isEqualTo(-1); // -1 = unobservable
        assertThat(Ndjson.longValue(json, "aotTrainingPid", -99)).isEqualTo(-1); // -1 = no trainer running
        assertThat(Ndjson.str(json, "httpUrl")).isNull(); // omitted entirely when http is off
        assertThat(Ndjson.str(json, "httpError")).isNull();
    }

    @Test
    void status_ack_carries_http_url_when_serving() {
        String json = EngineProtocol.statusAck(
                "1.2.3", 42, 1_000, 3, 0, false, 1, 2, 3, -1, -1, "http://127.0.0.1:8910/", null);
        assertThat(Ndjson.str(json, "httpUrl")).isEqualTo("http://127.0.0.1:8910/");
        assertThat(Ndjson.str(json, "httpError")).isNull();
    }

    @Test
    void status_ack_carries_http_error_when_bind_failed() {
        String json = EngineProtocol.statusAck(
                "1.2.3", 42, 1_000, 3, 0, false, 1, 2, 3, -1, -1, null, "Address already in use");
        assertThat(Ndjson.str(json, "httpUrl")).isNull();
        assertThat(Ndjson.str(json, "httpError")).isEqualTo("Address already in use");
    }

    @Test
    void shutdown_and_bye_are_distinct_types() {
        assertThat(EngineProtocol.typeOf(EngineProtocol.shutdown())).isEqualTo(EngineProtocol.SHUTDOWN);
        assertThat(EngineProtocol.typeOf(EngineProtocol.bye())).isEqualTo(EngineProtocol.BYE);
    }

    @Test
    void shutdown_carries_force_flag_and_defaults_false() {
        assertThat(Ndjson.bool(EngineProtocol.shutdown(), "force", true)).isFalse();
        assertThat(Ndjson.bool(EngineProtocol.shutdown(false), "force", true)).isFalse();
        assertThat(Ndjson.bool(EngineProtocol.shutdown(true), "force", false)).isTrue();
    }

    @Test
    void bye_reports_in_flight_jobs_and_draining() {
        String bye = EngineProtocol.bye(3, true);
        assertThat(EngineProtocol.typeOf(bye)).isEqualTo(EngineProtocol.BYE);
        assertThat(Ndjson.intValue(bye, "pipelines", -1)).isEqualTo(3);
        assertThat(Ndjson.bool(bye, "draining", false)).isTrue();
        // no-arg back-compat: 0 jobs, not draining
        assertThat(Ndjson.intValue(EngineProtocol.bye(), "pipelines", -1)).isEqualTo(0);
        assertThat(Ndjson.bool(EngineProtocol.bye(), "draining", true)).isFalse();
    }

    @Test
    void with_session_attaches_variant_env_and_jvm_in_one_validated_splice() {
        String base = EngineProtocol.ping();
        // Empty envelope: byte-identical.
        assertThat(EngineProtocol.withSession(base, null, java.util.Map.of(), null)).isEqualTo(base);
        // Variant + env ride every hosted request; env is the one flat-map encoding.
        String line = EngineProtocol.withSession(
                base, "release|tier=free", java.util.Map.of("KEY_PASS", "s3cret"), null);
        assertThat(EngineProtocol.variantOf(line)).isEqualTo("release|tier=free");
        assertThat(EngineProtocol.clientEnvOf(line)).containsExactly(java.util.Map.entry("KEY_PASS", "s3cret"));
        // A non-encoded line is rejected, not silently mangled.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> EngineProtocol.withSession("not-json", "release", java.util.Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void goal_finish_carries_its_kind_discriminator() {
        assertThat(Ndjson.str(EngineProtocol.goalFinish("/w", true), "kind")).isEqualTo("build");
        assertThat(Ndjson.str(EngineProtocol.goalFinishSync("/w", true, 3, 4), "kind")).isEqualTo("sync");
        assertThat(Ndjson.str(EngineProtocol.goalFinishLock("/w", true, 1, 2, 3), "kind")).isEqualTo("lock");
    }

    @Test
    void the_error_envelope_carries_a_code_and_message() {
        String e = EngineProtocol.error(EngineProtocol.ERR_SHUTTING_DOWN, "draining — retry");
        assertThat(EngineProtocol.typeOf(e)).isEqualTo(EngineProtocol.ERROR);
        assertThat(Ndjson.str(e, "code")).isEqualTo(EngineProtocol.ERR_SHUTTING_DOWN);
        assertThat(Ndjson.str(e, "message")).isEqualTo("draining — retry");
    }

    @Test
    void type_of_malformed_json_is_null() {
        assertThat(EngineProtocol.typeOf("not json")).isNull();
    }

    @Test
    void build_request_carries_freshen_lock() {
        String on = EngineProtocol.buildRequest(
                "/w", "/c", null, 1, null, false, false, 0, false, false, false, true);
        assertThat(Ndjson.bool(on, "freshenLock", false)).isTrue();
        String off = EngineProtocol.buildRequest(
                "/w", "/c", null, 1, null, false, false, 0, false, false, false, false);
        assertThat(Ndjson.bool(off, "freshenLock", true)).isFalse();
        // rebuild rides the session envelope, not the builder.
        assertThat(Ndjson.bool(EngineProtocol.withSession(off, null, null, null, true), "rebuild", false))
                .isTrue();
    }

    @Test
    void lock_request_round_trips_all_fields() {
        String json = EngineProtocol.lockRequest(
                "/work", "/cache", java.util.List.of("a", "b"), true, true, "http://repo", true, false, true);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.LOCK_REQUEST);
        assertThat(Ndjson.str(json, "dir")).isEqualTo("/work");
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
        assertThat(Ndjson.str(json, "dir")).isEqualTo("/work");
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
                "/w", "/c", null, "com.example.Main", null, null, null, null, false, false, false, false);
        assertThat(EngineProtocol.typeOf(none)).isEqualTo(EngineProtocol.IMAGE_REQUEST);
        assertThat(Ndjson.str(none, "tarball")).isNull();
        String defaulted = EngineProtocol.imageRequest(
                "/w", "/c", null, null, null, null, "", null, false, false, false, false);
        assertThat(Ndjson.str(defaulted, "tarball")).isEmpty();
        String explicit = EngineProtocol.imageRequest(
                "/w", "/c", null, null, "reg.io", "v2", "/out/img.tar", "podman", true, true, true, true);
        assertThat(Ndjson.str(explicit, "tarball")).isEqualTo("/out/img.tar");
        assertThat(Ndjson.str(explicit, "registry")).isEqualTo("reg.io");
        assertThat(Ndjson.str(explicit, "dockerExecutable")).isEqualTo("podman");
        assertThat(Ndjson.bool(explicit, "skipTests", false)).isTrue();
        // rebuild rides the session envelope (withSession), not the image builder.
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

    @Test
    void compile_request_round_trips_all_fields() {
        String json = EngineProtocol.compileRequest("/work", "/cache", "ci", true, false, true);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.COMPILE_REQUEST);
        assertThat(Ndjson.str(json, "dir")).isEqualTo("/work");
        assertThat(Ndjson.str(json, "cache")).isEqualTo("/cache");
        assertThat(Ndjson.str(json, "profile")).isEqualTo("ci");
        assertThat(Ndjson.bool(json, "offline", false)).isTrue();
        assertThat(Ndjson.bool(json, "force", true)).isFalse();
        assertThat(Ndjson.bool(json, "verbose", false)).isTrue();

        String noProfile = EngineProtocol.compileRequest("/w", "/c", null, false, false, false);
        assertThat(Ndjson.str(noProfile, "profile")).isNull();
    }

    @Test
    void native_request_round_trips_the_graal_home_map() {
        var graal = new java.util.LinkedHashMap<String, String>();
        graal.put("/work/app", "/graal/a");
        graal.put("/work/tool", "/graal/b");
        String json = EngineProtocol.nativeRequest(
                "/work",
                "/cache",
                "/jdks",
                "com.example.Main",
                true,
                false,
                true,
                false,
                java.util.List.of("-O2"),
                graal);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.NATIVE_REQUEST);
        assertThat(Ndjson.str(json, "dir")).isEqualTo("/work");
        assertThat(Ndjson.str(json, "jdksDir")).isEqualTo("/jdks");
        assertThat(Ndjson.str(json, "mainClass")).isEqualTo("com.example.Main");
        assertThat(Ndjson.bool(json, "skipTests", false)).isTrue();
        assertThat(Ndjson.bool(json, "force", false)).isTrue();
        assertThat(Ndjson.strArray(json, "extraArgs")).containsExactly("-O2");
        assertThat(Ndjson.strMap(json, "graalHomes")).containsExactly(
                java.util.Map.entry("/work/app", "/graal/a"), java.util.Map.entry("/work/tool", "/graal/b"));
    }

    @Test
    void install_request_round_trips_all_fields() {
        String json = EngineProtocol.installRequest("/work", "/cache", "/home/u/.m2", "/graal", true, false, false, true);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.INSTALL_REQUEST);
        assertThat(Ndjson.str(json, "dir")).isEqualTo("/work");
        assertThat(Ndjson.str(json, "m2Dir")).isEqualTo("/home/u/.m2");
        assertThat(Ndjson.str(json, "graalHome")).isEqualTo("/graal");
        assertThat(Ndjson.bool(json, "skipTests", false)).isTrue();
        assertThat(Ndjson.bool(json, "verbose", false)).isTrue();

        String jvmOnly = EngineProtocol.installRequest("/w", "/c", "/m2", null, false, false, false, false);
        assertThat(Ndjson.str(jvmOnly, "graalHome")).isNull();
    }

    @Test
    void git_fetch_request_and_finish_variant_round_trip() {
        String req = EngineProtocol.gitFetchRequest(
                "https://github.com/o/r.git", "github.com/o/r", "v1.2", "/cache", true, false);
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.GIT_FETCH_REQUEST);
        assertThat(Ndjson.str(req, "url")).isEqualTo("https://github.com/o/r.git");
        assertThat(Ndjson.str(req, "canonicalUrl")).isEqualTo("github.com/o/r");
        assertThat(Ndjson.str(req, "ref")).isEqualTo("v1.2");
        assertThat(Ndjson.bool(req, "refresh", false)).isTrue();
        assertThat(Ndjson.bool(req, "requireJkToml", true)).isFalse();

        String finish = EngineProtocol.goalFinishGitFetch("", true, "/cache/git/co/abc", "abc123");
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.GOAL_FINISH);
        assertThat(Ndjson.bool(finish, "success", false)).isTrue();
        assertThat(Ndjson.str(finish, "gitCheckout")).isEqualTo("/cache/git/co/abc");
        assertThat(Ndjson.str(finish, "gitSha")).isEqualTo("abc123");

        String failed = EngineProtocol.goalFinishGitFetch("", false, null, null);
        assertThat(Ndjson.str(failed, "gitCheckout")).isNull();
        assertThat(Ndjson.str(failed, "gitSha")).isNull();
    }

    @Test
    void explain_request_carries_the_eta_inputs() {
        String json = EngineProtocol.explainRequest("/work", "/cache", 4, true, "ci", "/jdks", true, true, false);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.EXPLAIN_REQUEST);
        assertThat(Ndjson.str(json, "dir")).isEqualTo("/work");
        assertThat(Ndjson.intValue(json, "workers", -1)).isEqualTo(4);
        assertThat(Ndjson.bool(json, "skipTests", false)).isTrue();
        assertThat(Ndjson.str(json, "profile")).isEqualTo("ci");
        assertThat(Ndjson.str(json, "jdksDir")).isEqualTo("/jdks");
        assertThat(Ndjson.bool(json, "serial", false)).isTrue();
        assertThat(Ndjson.bool(json, "parallelTests", false)).isTrue();

        String defaults = EngineProtocol.explainRequest("/w", "/c", 1, false, null, null, false, false, false);
        assertThat(Ndjson.str(defaults, "profile")).isNull();
        assertThat(Ndjson.str(defaults, "jdksDir")).isNull();
    }

    @Test
    void tool_resolve_request_and_finish_variant_round_trip() {
        String req = EngineProtocol.toolResolveRequest(
                "com.example:widget-cli:1.0.0",
                java.util.List.of("com.example:extra@1.2"),
                "widget",
                "com.example.Main",
                "http://repo",
                "/cache");
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.TOOL_RESOLVE_REQUEST);
        assertThat(Ndjson.str(req, "coord")).isEqualTo("com.example:widget-cli:1.0.0");
        assertThat(Ndjson.strArray(req, "with")).containsExactly("com.example:extra@1.2");
        assertThat(Ndjson.str(req, "bin")).isEqualTo("widget");
        assertThat(Ndjson.str(req, "mainClass")).isEqualTo("com.example.Main");
        assertThat(Ndjson.str(req, "repoUrl")).isEqualTo("http://repo");
        assertThat(Ndjson.str(req, "cache")).isEqualTo("/cache");

        String defaults = EngineProtocol.toolResolveRequest("g:a:1", java.util.List.of(), "a", null, null, "/c");
        assertThat(Ndjson.str(defaults, "mainClass")).isNull();
        assertThat(Ndjson.str(defaults, "repoUrl")).isNull();
        assertThat(Ndjson.strArray(defaults, "with")).isEmpty();

        String finish = EngineProtocol.goalFinishTool(
                "", true, "com.example:widget-cli:1.0.0", "com.example.Main",
                java.util.List.of("/cas/aa/1.jar", "/cas/bb/2.jar"));
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.GOAL_FINISH);
        assertThat(Ndjson.bool(finish, "success", false)).isTrue();
        assertThat(Ndjson.str(finish, "toolCoord")).isEqualTo("com.example:widget-cli:1.0.0");
        assertThat(Ndjson.str(finish, "toolMainClass")).isEqualTo("com.example.Main");
        assertThat(Ndjson.strArray(finish, "toolClasspath")).containsExactly("/cas/aa/1.jar", "/cas/bb/2.jar");

        String failed = EngineProtocol.goalFinishTool("", false, null, null, java.util.List.of());
        assertThat(Ndjson.str(failed, "toolCoord")).isNull();
        assertThat(Ndjson.str(failed, "toolMainClass")).isNull();
        assertThat(Ndjson.strArray(failed, "toolClasspath")).isEmpty();
    }

    @Test
    void cache_prune_request_round_trips_all_fields() {
        String json = EngineProtocol.cachePruneRequest("prune", "/cache", 14, true, true, "20G", true);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.CACHE_PRUNE_REQUEST);
        assertThat(Ndjson.str(json, "op")).isEqualTo("prune");
        assertThat(Ndjson.str(json, "cache")).isEqualTo("/cache");
        assertThat(Ndjson.intValue(json, "olderThanDays", -1)).isEqualTo(14);
        assertThat(Ndjson.bool(json, "dryRun", false)).isTrue();
        assertThat(Ndjson.bool(json, "sweep", false)).isTrue();
        assertThat(Ndjson.str(json, "maxSize")).isEqualTo("20G");
        assertThat(Ndjson.bool(json, "includeJkTmp", false)).isTrue();

        String purge = EngineProtocol.cachePruneRequest("purge", "/c", 0, false, false, null, false);
        assertThat(Ndjson.str(purge, "op")).isEqualTo("purge");
        assertThat(Ndjson.str(purge, "maxSize")).isNull();
    }

    @Test
    void prune_wait_round_trips_pipelines_and_external() {
        String inEngine = EngineProtocol.pruneWait(3, false);
        assertThat(EngineProtocol.typeOf(inEngine)).isEqualTo(EngineProtocol.PRUNE_WAIT);
        assertThat(Ndjson.intValue(inEngine, "pipelines", -1)).isEqualTo(3);
        assertThat(Ndjson.bool(inEngine, "external", true)).isFalse();

        String external = EngineProtocol.pruneWait(0, true);
        assertThat(Ndjson.bool(external, "external", false)).isTrue();
    }

    @Test
    void cache_finish_variant_round_trips_the_summary() {
        String json = EngineProtocol.goalFinishCache("", true, 12, 34_567, 2, -1);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.GOAL_FINISH);
        assertThat(Ndjson.bool(json, "success", false)).isTrue();
        assertThat(Ndjson.longValue(json, "cacheFiles", -99)).isEqualTo(12);
        assertThat(Ndjson.longValue(json, "cacheBytes", -99)).isEqualTo(34_567);
        assertThat(Ndjson.longValue(json, "cacheReachableEvicted", -99)).isEqualTo(2);
        assertThat(Ndjson.longValue(json, "cacheRepoLinks", -99)).isEqualTo(-1); // n/a for prune
    }
}
