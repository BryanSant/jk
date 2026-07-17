// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineProtocolTest {

    @Test
    void hello_round_trips_the_version() {
        String json = EngineProtocol.hello("1.2.3");
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.HELLO);
        assertThat(Jsonl.str(json, "version")).isEqualTo("1.2.3");
    }

    @Test
    void hello_ack_round_trips_version_pid_start_time_and_build_id() {
        String json = EngineProtocol.helloAck("1.2.3", 4321, 999_000, true, "abc123def456");
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.HELLO_ACK);
        assertThat(Jsonl.str(json, "version")).isEqualTo("1.2.3");
        assertThat(Jsonl.longValue(json, "pid", -1)).isEqualTo(4321);
        assertThat(Jsonl.longValue(json, "startedAt", -1)).isEqualTo(999_000);
        assertThat(Jsonl.bool(json, "draining", false)).isTrue();
        assertThat(Jsonl.str(json, "buildId")).isEqualTo("abc123def456");
        // Identity-less contexts answer an EMPTY buildId ("no opinion"), never null.
        assertThat(Jsonl.str(EngineProtocol.helloAck("1.2.3", 1, 1, false, null), "buildId"))
                .isEmpty();
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
        assertThat(Jsonl.str(json, "version")).isEqualTo("1.2.3");
        assertThat(Jsonl.longValue(json, "pid", -1)).isEqualTo(42);
        assertThat(Jsonl.longValue(json, "startedAt", -1)).isEqualTo(1_000);
        assertThat(Jsonl.intValue(json, "activeRequests", -99)).isEqualTo(3);
        assertThat(Jsonl.intValue(json, "activePipelines", -99)).isEqualTo(7);
        assertThat(Jsonl.bool(json, "draining", false)).isTrue();
        assertThat(Jsonl.longValue(json, "heapUsedBytes", -99)).isEqualTo(18_000_000);
        assertThat(Jsonl.longValue(json, "heapCommittedBytes", -99)).isEqualTo(42_000_000);
        assertThat(Jsonl.longValue(json, "heapMaxBytes", -99)).isEqualTo(268_435_456);
        assertThat(Jsonl.longValue(json, "rssBytes", -99)).isEqualTo(-1); // -1 = unobservable
        assertThat(Jsonl.longValue(json, "aotTrainingPid", -99)).isEqualTo(-1); // -1 = no trainer running
        assertThat(Jsonl.str(json, "httpUrl")).isNull(); // omitted entirely when http is off
        assertThat(Jsonl.str(json, "httpError")).isNull();
    }

    @Test
    void status_ack_carries_http_url_when_serving() {
        String json = EngineProtocol.statusAck(
                "1.2.3", 42, 1_000, 3, 0, false, 1, 2, 3, -1, -1, "http://127.0.0.1:8910/", null);
        assertThat(Jsonl.str(json, "httpUrl")).isEqualTo("http://127.0.0.1:8910/");
        assertThat(Jsonl.str(json, "httpError")).isNull();
    }

    @Test
    void status_ack_carries_http_error_when_bind_failed() {
        String json = EngineProtocol.statusAck(
                "1.2.3", 42, 1_000, 3, 0, false, 1, 2, 3, -1, -1, null, "Address already in use");
        assertThat(Jsonl.str(json, "httpUrl")).isNull();
        assertThat(Jsonl.str(json, "httpError")).isEqualTo("Address already in use");
    }

    @Test
    void shutdown_and_bye_are_distinct_types() {
        assertThat(EngineProtocol.typeOf(EngineProtocol.shutdown())).isEqualTo(EngineProtocol.SHUTDOWN);
        assertThat(EngineProtocol.typeOf(EngineProtocol.bye())).isEqualTo(EngineProtocol.BYE);
    }

    @Test
    void shutdown_carries_force_flag_and_defaults_false() {
        assertThat(Jsonl.bool(EngineProtocol.shutdown(), "force", true)).isFalse();
        assertThat(Jsonl.bool(EngineProtocol.shutdown(false), "force", true)).isFalse();
        assertThat(Jsonl.bool(EngineProtocol.shutdown(true), "force", false)).isTrue();
    }

    @Test
    void bye_reports_in_flight_jobs_and_draining() {
        String bye = EngineProtocol.bye(3, true);
        assertThat(EngineProtocol.typeOf(bye)).isEqualTo(EngineProtocol.BYE);
        assertThat(Jsonl.intValue(bye, "pipelines", -1)).isEqualTo(3);
        assertThat(Jsonl.bool(bye, "draining", false)).isTrue();
        // no-arg back-compat: 0 jobs, not draining
        assertThat(Jsonl.intValue(EngineProtocol.bye(), "pipelines", -1)).isEqualTo(0);
        assertThat(Jsonl.bool(EngineProtocol.bye(), "draining", true)).isFalse();
    }

    @Test
    void with_session_attaches_variant_env_and_jvm_in_one_validated_splice() {
        String base = EngineProtocol.ping();
        // Empty envelope: byte-identical.
        assertThat(EngineProtocol.withSession(base, null, Map.of(), null)).isEqualTo(base);
        // Variant + env ride every hosted request; env is the one flat-map encoding.
        String line = EngineProtocol.withSession(base, "release|tier=free", Map.of("KEY_PASS", "s3cret"), null);
        assertThat(EngineProtocol.variantOf(line)).isEqualTo("release|tier=free");
        assertThat(EngineProtocol.clientEnvOf(line)).containsExactly(Map.entry("KEY_PASS", "s3cret"));
        // A non-encoded line is rejected, not silently mangled.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> EngineProtocol.withSession("not-json", "release", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void goal_finish_carries_its_kind_discriminator() {
        assertThat(Jsonl.str(EngineProtocol.pipelineFinish("/w", true), "kind"))
                .isEqualTo("build");
        assertThat(Jsonl.str(EngineProtocol.pipelineFinishSync("/w", true, 3, 4), "kind"))
                .isEqualTo("sync");
        assertThat(Jsonl.str(EngineProtocol.pipelineFinishLock("/w", true, 1, 2, 3), "kind"))
                .isEqualTo("lock");
    }

    @Test
    void the_error_envelope_carries_a_code_and_message() {
        String e = EngineProtocol.error(EngineProtocol.ERR_SHUTTING_DOWN, "draining — retry");
        assertThat(EngineProtocol.typeOf(e)).isEqualTo(EngineProtocol.ERROR);
        assertThat(Jsonl.str(e, "code")).isEqualTo(EngineProtocol.ERR_SHUTTING_DOWN);
        assertThat(Jsonl.str(e, "message")).isEqualTo("draining — retry");
    }

    @Test
    void type_of_malformed_json_is_null() {
        assertThat(EngineProtocol.typeOf("not json")).isNull();
    }

    @Test
    void build_request_carries_freshen_lock() {
        String on = EngineProtocol.buildRequest("/w", "/c", null, 1, null, false, false, 0, false, false, false, true);
        assertThat(Jsonl.bool(on, "freshenLock", false)).isTrue();
        String off =
                EngineProtocol.buildRequest("/w", "/c", null, 1, null, false, false, 0, false, false, false, false);
        assertThat(Jsonl.bool(off, "freshenLock", true)).isFalse();
        // rebuild rides the session envelope, not the builder.
        assertThat(Jsonl.bool(EngineProtocol.withSession(off, null, null, null, true), "rebuild", false))
                .isTrue();
    }

    @Test
    void lock_request_round_trips_all_fields() {
        String json = EngineProtocol.lockRequest(
                "/work", "/cache", List.of("a", "b"), true, true, "http://repo", true, false, true);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.LOCK_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.str(json, "cache")).isEqualTo("/cache");
        assertThat(Jsonl.strArray(json, "features")).containsExactly("a", "b");
        assertThat(Jsonl.bool(json, "noDefaultFeatures", false)).isTrue();
        assertThat(Jsonl.bool(json, "sources", false)).isTrue();
        assertThat(Jsonl.str(json, "repoUrl")).isEqualTo("http://repo");
        assertThat(Jsonl.bool(json, "offline", false)).isTrue();
        assertThat(Jsonl.bool(json, "force", true)).isFalse();
        assertThat(Jsonl.bool(json, "verbose", false)).isTrue();
    }

    @Test
    void lock_request_null_repo_url_decodes_as_absent() {
        String json = EngineProtocol.lockRequest("/w", "/c", List.of(), false, false, null, false, false, false);
        assertThat(Jsonl.str(json, "repoUrl")).isNull();
    }

    @Test
    void update_request_round_trips_the_git_splice_fields() {
        String json = EngineProtocol.updateRequest(
                "/work", "/cache", List.of(), false, null, true, "mylib", false, true, false);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.UPDATE_REQUEST);
        assertThat(Jsonl.bool(json, "gitOnly", false)).isTrue();
        assertThat(Jsonl.str(json, "gitTarget")).isEqualTo("mylib");
        assertThat(Jsonl.bool(json, "force", false)).isTrue();
    }

    @Test
    void sync_request_round_trips_all_fields() {
        String json =
                EngineProtocol.syncRequest("/work", "/cache", "/jdks", "http://repo", true, false, true, true, false);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.SYNC_REQUEST);
        assertThat(Jsonl.str(json, "jdksDir")).isEqualTo("/jdks");
        assertThat(Jsonl.str(json, "repoUrl")).isEqualTo("http://repo");
        assertThat(Jsonl.bool(json, "sources", false)).isTrue();
        assertThat(Jsonl.bool(json, "offline", true)).isFalse();
        assertThat(Jsonl.bool(json, "force", false)).isTrue();
        assertThat(Jsonl.bool(json, "refresh", false)).isTrue();
    }

    @Test
    void lock_module_and_package_events_round_trip() {
        String module = EngineProtocol.lockModule("/work/api", "com.example:api");
        assertThat(EngineProtocol.typeOf(module)).isEqualTo(EngineProtocol.LOCK_MODULE);
        assertThat(Jsonl.str(module, "dir")).isEqualTo("/work/api");
        assertThat(Jsonl.str(module, "coord")).isEqualTo("com.example:api");

        String pkg = EngineProtocol.lockPackage("/work/api", "com.foo:leaf", "1.0");
        assertThat(EngineProtocol.typeOf(pkg)).isEqualTo(EngineProtocol.LOCK_PACKAGE);
        assertThat(Jsonl.str(pkg, "name")).isEqualTo("com.foo:leaf");
        assertThat(Jsonl.str(pkg, "version")).isEqualTo("1.0");
    }

    @Test
    void goal_finish_lock_variant_carries_the_lockfile_counts() {
        String json = EngineProtocol.pipelineFinishLock("", true, 13, 2, 1);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.PIPELINE_FINISH);
        assertThat(Jsonl.bool(json, "success", false)).isTrue();
        assertThat(Jsonl.longValue(json, "lockPackages", -1)).isEqualTo(13);
        assertThat(Jsonl.longValue(json, "lockSources", -1)).isEqualTo(2);
        assertThat(Jsonl.longValue(json, "lockPlugins", -1)).isEqualTo(1);
    }

    @Test
    void goal_finish_sync_variant_carries_the_summary_counts() {
        String json = EngineProtocol.pipelineFinishSync("", true, 7, 42);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.PIPELINE_FINISH);
        assertThat(Jsonl.longValue(json, "syncFetched", -1)).isEqualTo(7);
        assertThat(Jsonl.longValue(json, "syncUpToDate", -1)).isEqualTo(42);
    }

    @Test
    void lock_finish_round_trips_outcome_errors_and_refreshed_count() {
        String json = EngineProtocol.lockFinish(false, 6, List.of("boom", "again"), 3);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.LOCK_FINISH);
        assertThat(Jsonl.bool(json, "success", true)).isFalse();
        assertThat(Jsonl.intValue(json, "exitCode", -1)).isEqualTo(6);
        assertThat(Jsonl.strArray(json, "errors")).containsExactly("boom", "again");
        assertThat(Jsonl.intValue(json, "refreshed", -1)).isEqualTo(3);
    }

    // ---- Wave 2: hosted worker commands ------------------------------------------------------------

    @Test
    void audit_request_round_trips_all_fields() {
        String json = EngineProtocol.auditRequest("/work", "/cache", "HIGH", "http://osv/batch", "http://osv/vulns/");
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.AUDIT_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.str(json, "cache")).isEqualTo("/cache");
        assertThat(Jsonl.str(json, "severity")).isEqualTo("HIGH");
        assertThat(Jsonl.str(json, "osvBatchUrl")).isEqualTo("http://osv/batch");
        assertThat(Jsonl.str(json, "osvVulnsUrl")).isEqualTo("http://osv/vulns/");
        // null overrides decode as absent (the real OSV endpoints)
        assertThat(Jsonl.str(EngineProtocol.auditRequest("/w", "/c", "LOW", null, null), "osvBatchUrl"))
                .isNull();
    }

    @Test
    void audit_finding_event_round_trips_the_worker_fields() {
        String json = EngineProtocol.auditFinding("", "com.foo:leaf", "1.0", "GHSA-x", "HIGH", "bad news");
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.AUDIT_FINDING);
        assertThat(Jsonl.str(json, "module")).isEqualTo("com.foo:leaf");
        assertThat(Jsonl.str(json, "version")).isEqualTo("1.0");
        assertThat(Jsonl.str(json, "vulnId")).isEqualTo("GHSA-x");
        assertThat(Jsonl.str(json, "severity")).isEqualTo("HIGH");
        assertThat(Jsonl.str(json, "summary")).isEqualTo("bad news");
    }

    @Test
    void format_request_round_trips_the_resolved_styles() {
        String json = EngineProtocol.formatRequest(
                "/work", "/cache", true, "palantir", "kotlinlang", false, "/rw.yml", true, false);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.FORMAT_REQUEST);
        assertThat(Jsonl.bool(json, "check", false)).isTrue();
        assertThat(Jsonl.str(json, "javaStyle")).isEqualTo("palantir");
        assertThat(Jsonl.str(json, "kotlinStyle")).isEqualTo("kotlinlang");
        assertThat(Jsonl.bool(json, "optimizeImports", true)).isFalse();
        assertThat(Jsonl.str(json, "rewriteConfig")).isEqualTo("/rw.yml");
        assertThat(Jsonl.bool(json, "offline", false)).isTrue();
    }

    @Test
    void format_file_event_and_finish_variant_round_trip() {
        String file = EngineProtocol.formatFile("", "/src/A.java", "changed", null, 3, 12);
        assertThat(EngineProtocol.typeOf(file)).isEqualTo(EngineProtocol.FORMAT_FILE);
        assertThat(Jsonl.str(file, "path")).isEqualTo("/src/A.java");
        assertThat(Jsonl.str(file, "status")).isEqualTo("changed");
        assertThat(Jsonl.intValue(file, "index", -1)).isEqualTo(3);
        assertThat(Jsonl.intValue(file, "total", -1)).isEqualTo(12);

        String finish = EngineProtocol.pipelineFinishFormat("", true, 2, 9, 1, 12, 1);
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.PIPELINE_FINISH);
        assertThat(Jsonl.intValue(finish, "formatChanged", -1)).isEqualTo(2);
        assertThat(Jsonl.intValue(finish, "formatClean", -1)).isEqualTo(9);
        assertThat(Jsonl.intValue(finish, "formatErrors", -1)).isEqualTo(1);
        assertThat(Jsonl.intValue(finish, "formatTotal", -1)).isEqualTo(12);
        assertThat(Jsonl.intValue(finish, "formatWorkerExit", -1)).isEqualTo(1);
    }

    @Test
    void publish_request_round_trips_the_credential_fields() {
        String json = EngineProtocol.publishRequest(
                "/work",
                "/cache",
                "https://repo/m2",
                "us-east-1",
                null,
                "/out/app.jar",
                true,
                false,
                "/key.asc",
                "s3cret",
                false,
                true,
                true,
                "basic",
                "alice",
                "hunter2",
                null,
                false);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.PUBLISH_REQUEST);
        assertThat(Jsonl.str(json, "repoUrl")).isEqualTo("https://repo/m2");
        assertThat(Jsonl.str(json, "region")).isEqualTo("us-east-1");
        assertThat(Jsonl.str(json, "endpoint")).isNull();
        assertThat(Jsonl.str(json, "jar")).isEqualTo("/out/app.jar");
        assertThat(Jsonl.bool(json, "allowSnapshot", false)).isTrue();
        assertThat(Jsonl.str(json, "keyFile")).isEqualTo("/key.asc");
        assertThat(Jsonl.str(json, "gpgPassphrase")).isEqualTo("s3cret");
        assertThat(Jsonl.bool(json, "slsa", false)).isTrue();
        assertThat(Jsonl.bool(json, "sbom", false)).isTrue();
        assertThat(Jsonl.str(json, "authType")).isEqualTo("basic");
        assertThat(Jsonl.str(json, "user")).isEqualTo("alice");
        assertThat(Jsonl.str(json, "pass")).isEqualTo("hunter2");
        assertThat(Jsonl.str(json, "token")).isNull();

        String finish = EngineProtocol.pipelineFinishPublish("", true, 9);
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.PIPELINE_FINISH);
        assertThat(Jsonl.intValue(finish, "publishFiles", -1)).isEqualTo(9);
    }

    @Test
    void image_request_keeps_the_tarball_tristate() {
        String none = EngineProtocol.imageRequest(
                "/w", "/c", null, "com.example.Main", null, null, null, null, false, false, false, false);
        assertThat(EngineProtocol.typeOf(none)).isEqualTo(EngineProtocol.IMAGE_REQUEST);
        assertThat(Jsonl.str(none, "tarball")).isNull();
        String defaulted =
                EngineProtocol.imageRequest("/w", "/c", null, null, null, null, "", null, false, false, false, false);
        assertThat(Jsonl.str(defaulted, "tarball")).isEmpty();
        String explicit = EngineProtocol.imageRequest(
                "/w", "/c", null, null, "reg.io", "v2", "/out/img.tar", "podman", true, true, true, true);
        assertThat(Jsonl.str(explicit, "tarball")).isEqualTo("/out/img.tar");
        assertThat(Jsonl.str(explicit, "registry")).isEqualTo("reg.io");
        assertThat(Jsonl.str(explicit, "dockerExecutable")).isEqualTo("podman");
        assertThat(Jsonl.bool(explicit, "skipTests", false)).isTrue();
        // rebuild rides the session envelope (withSession), not the image builder.
    }

    @Test
    void goal_finish_image_variant_carries_the_success_tail_fields_and_test_counts() {
        String json =
                EngineProtocol.pipelineFinishImage("", true, 12, 12, 0, 0, "reg.io/app:1.0", null, "app", "1.0", null);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.PIPELINE_FINISH);
        assertThat(Jsonl.longValue(json, "testTotal", -1)).isEqualTo(12);
        assertThat(Jsonl.str(json, "imageRef")).isEqualTo("reg.io/app:1.0");
        assertThat(Jsonl.str(json, "imageTarball")).isNull();
        assertThat(Jsonl.str(json, "imageName")).isEqualTo("app");
        assertThat(Jsonl.str(json, "imageVersion")).isEqualTo("1.0");
        assertThat(Jsonl.str(json, "imageDaemonExe")).isNull();
    }

    @Test
    void import_request_note_and_finish_variant_round_trip() {
        String req = EngineProtocol.importRequest("/p/pom.xml", "/p/jk.toml", "/p", "/tmp/jk", true, null, "/cache");
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.IMPORT_REQUEST);
        assertThat(Jsonl.str(req, "source")).isEqualTo("/p/pom.xml");
        assertThat(Jsonl.str(req, "out")).isEqualTo("/p/jk.toml");
        assertThat(Jsonl.bool(req, "force", false)).isTrue();
        assertThat(Jsonl.str(req, "report")).isNull();

        String note = EngineProtocol.importNote("", "wrote", "/p/jk.toml");
        assertThat(EngineProtocol.typeOf(note)).isEqualTo(EngineProtocol.IMPORT_NOTE);
        assertThat(Jsonl.str(note, "kind")).isEqualTo("wrote");
        assertThat(Jsonl.str(note, "text")).isEqualTo("/p/jk.toml");

        String finish = EngineProtocol.pipelineFinishImport("", true, 0, 3, null, null);
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.PIPELINE_FINISH);
        assertThat(Jsonl.intValue(finish, "importExit", -1)).isEqualTo(0);
        assertThat(Jsonl.intValue(finish, "importWarnings", -1)).isEqualTo(3);
        assertThat(Jsonl.str(finish, "importError")).isNull();
    }

    @Test
    void provision_request_and_result_round_trip() {
        String req = EngineProtocol.provisionRequest("/cache", "/proj", "/cache/tools", true, true);
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.PROVISION_REQUEST);
        assertThat(Jsonl.str(req, "projectDir")).isEqualTo("/proj");
        assertThat(Jsonl.str(req, "toolsRoot")).isEqualTo("/cache/tools");
        assertThat(Jsonl.bool(req, "noDiscover", false)).isTrue();
        assertThat(Jsonl.bool(req, "gradle", false)).isTrue();

        String result =
                EngineProtocol.provisionResult("/cache/tools/mvn/bin/mvn", "3.9.9", "DOWNLOADED", null, 0, null);
        assertThat(EngineProtocol.typeOf(result)).isEqualTo(EngineProtocol.PROVISION_RESULT);
        assertThat(Jsonl.str(result, "bin")).isEqualTo("/cache/tools/mvn/bin/mvn");
        assertThat(Jsonl.str(result, "version")).isEqualTo("3.9.9");
        assertThat(Jsonl.str(result, "source")).isEqualTo("DOWNLOADED");
        assertThat(Jsonl.str(result, "error")).isNull();
        assertThat(Jsonl.intValue(result, "exit", -1)).isEqualTo(0);
    }

    @Test
    void compile_request_round_trips_all_fields() {
        String json = EngineProtocol.compileRequest("/work", "/cache", "ci", true, false, true);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.COMPILE_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.str(json, "cache")).isEqualTo("/cache");
        assertThat(Jsonl.str(json, "profile")).isEqualTo("ci");
        assertThat(Jsonl.bool(json, "offline", false)).isTrue();
        assertThat(Jsonl.bool(json, "force", true)).isFalse();
        assertThat(Jsonl.bool(json, "verbose", false)).isTrue();

        String noProfile = EngineProtocol.compileRequest("/w", "/c", null, false, false, false);
        assertThat(Jsonl.str(noProfile, "profile")).isNull();
    }

    @Test
    void native_request_round_trips_the_graal_home_map() {
        var graal = new LinkedHashMap<String, String>();
        graal.put("/work/app", "/graal/a");
        graal.put("/work/tool", "/graal/b");
        String json = EngineProtocol.nativeRequest(
                "/work", "/cache", "/jdks", "com.example.Main", true, false, true, false, List.of("-O2"), graal);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.NATIVE_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.str(json, "jdksDir")).isEqualTo("/jdks");
        assertThat(Jsonl.str(json, "mainClass")).isEqualTo("com.example.Main");
        assertThat(Jsonl.bool(json, "skipTests", false)).isTrue();
        assertThat(Jsonl.bool(json, "force", false)).isTrue();
        assertThat(Jsonl.strArray(json, "extraArgs")).containsExactly("-O2");
        assertThat(Jsonl.strMap(json, "graalHomes"))
                .containsExactly(Map.entry("/work/app", "/graal/a"), Map.entry("/work/tool", "/graal/b"));
    }

    @Test
    void install_request_round_trips_all_fields() {
        String json =
                EngineProtocol.installRequest("/work", "/cache", "/home/u/.m2", "/graal", true, false, false, true);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.INSTALL_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.str(json, "m2Dir")).isEqualTo("/home/u/.m2");
        assertThat(Jsonl.str(json, "graalHome")).isEqualTo("/graal");
        assertThat(Jsonl.bool(json, "skipTests", false)).isTrue();
        assertThat(Jsonl.bool(json, "verbose", false)).isTrue();

        String jvmOnly = EngineProtocol.installRequest("/w", "/c", "/m2", null, false, false, false, false);
        assertThat(Jsonl.str(jvmOnly, "graalHome")).isNull();
    }

    @Test
    void git_fetch_request_and_finish_variant_round_trip() {
        String req = EngineProtocol.gitFetchRequest(
                "https://github.com/o/r.git", "github.com/o/r", "v1.2", "/cache", true, false);
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.GIT_FETCH_REQUEST);
        assertThat(Jsonl.str(req, "url")).isEqualTo("https://github.com/o/r.git");
        assertThat(Jsonl.str(req, "canonicalUrl")).isEqualTo("github.com/o/r");
        assertThat(Jsonl.str(req, "ref")).isEqualTo("v1.2");
        assertThat(Jsonl.bool(req, "refresh", false)).isTrue();
        assertThat(Jsonl.bool(req, "requireJkToml", true)).isFalse();

        String finish = EngineProtocol.pipelineFinishGitFetch("", true, "/cache/git/co/abc", "abc123");
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.PIPELINE_FINISH);
        assertThat(Jsonl.bool(finish, "success", false)).isTrue();
        assertThat(Jsonl.str(finish, "gitCheckout")).isEqualTo("/cache/git/co/abc");
        assertThat(Jsonl.str(finish, "gitSha")).isEqualTo("abc123");

        String failed = EngineProtocol.pipelineFinishGitFetch("", false, null, null);
        assertThat(Jsonl.str(failed, "gitCheckout")).isNull();
        assertThat(Jsonl.str(failed, "gitSha")).isNull();
    }

    @Test
    void explain_request_carries_the_eta_inputs() {
        String json = EngineProtocol.explainRequest("/work", "/cache", 4, true, "ci", "/jdks", true, true, false);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.EXPLAIN_REQUEST);
        assertThat(Jsonl.str(json, "dir")).isEqualTo("/work");
        assertThat(Jsonl.intValue(json, "workers", -1)).isEqualTo(4);
        assertThat(Jsonl.bool(json, "skipTests", false)).isTrue();
        assertThat(Jsonl.str(json, "profile")).isEqualTo("ci");
        assertThat(Jsonl.str(json, "jdksDir")).isEqualTo("/jdks");
        assertThat(Jsonl.bool(json, "serial", false)).isTrue();
        assertThat(Jsonl.bool(json, "parallelTests", false)).isTrue();

        String defaults = EngineProtocol.explainRequest("/w", "/c", 1, false, null, null, false, false, false);
        assertThat(Jsonl.str(defaults, "profile")).isNull();
        assertThat(Jsonl.str(defaults, "jdksDir")).isNull();
    }

    @Test
    void tool_resolve_request_and_finish_variant_round_trip() {
        String req = EngineProtocol.toolResolveRequest(
                "com.example:widget-cli:1.0.0",
                List.of("com.example:extra@1.2"),
                "widget",
                "com.example.Main",
                "http://repo",
                "/cache");
        assertThat(EngineProtocol.typeOf(req)).isEqualTo(EngineProtocol.TOOL_RESOLVE_REQUEST);
        assertThat(Jsonl.str(req, "coord")).isEqualTo("com.example:widget-cli:1.0.0");
        assertThat(Jsonl.strArray(req, "with")).containsExactly("com.example:extra@1.2");
        assertThat(Jsonl.str(req, "bin")).isEqualTo("widget");
        assertThat(Jsonl.str(req, "mainClass")).isEqualTo("com.example.Main");
        assertThat(Jsonl.str(req, "repoUrl")).isEqualTo("http://repo");
        assertThat(Jsonl.str(req, "cache")).isEqualTo("/cache");

        String defaults = EngineProtocol.toolResolveRequest("g:a:1", List.of(), "a", null, null, "/c");
        assertThat(Jsonl.str(defaults, "mainClass")).isNull();
        assertThat(Jsonl.str(defaults, "repoUrl")).isNull();
        assertThat(Jsonl.strArray(defaults, "with")).isEmpty();

        String finish = EngineProtocol.pipelineFinishTool(
                "",
                true,
                "com.example:widget-cli:1.0.0",
                "com.example.Main",
                List.of("/cas/aa/1.jar", "/cas/bb/2.jar"));
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.PIPELINE_FINISH);
        assertThat(Jsonl.bool(finish, "success", false)).isTrue();
        assertThat(Jsonl.str(finish, "toolCoord")).isEqualTo("com.example:widget-cli:1.0.0");
        assertThat(Jsonl.str(finish, "toolMainClass")).isEqualTo("com.example.Main");
        assertThat(Jsonl.strArray(finish, "toolClasspath")).containsExactly("/cas/aa/1.jar", "/cas/bb/2.jar");

        String failed = EngineProtocol.pipelineFinishTool("", false, null, null, List.of());
        assertThat(Jsonl.str(failed, "toolCoord")).isNull();
        assertThat(Jsonl.str(failed, "toolMainClass")).isNull();
        assertThat(Jsonl.strArray(failed, "toolClasspath")).isEmpty();
    }

    @Test
    void cache_prune_request_round_trips_all_fields() {
        String json = EngineProtocol.cachePruneRequest("prune", "/cache", 14, true, true, "20G", true);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.CACHE_PRUNE_REQUEST);
        assertThat(Jsonl.str(json, "op")).isEqualTo("prune");
        assertThat(Jsonl.str(json, "cache")).isEqualTo("/cache");
        assertThat(Jsonl.intValue(json, "olderThanDays", -1)).isEqualTo(14);
        assertThat(Jsonl.bool(json, "dryRun", false)).isTrue();
        assertThat(Jsonl.bool(json, "sweep", false)).isTrue();
        assertThat(Jsonl.str(json, "maxSize")).isEqualTo("20G");
        assertThat(Jsonl.bool(json, "includeJkTmp", false)).isTrue();

        String purge = EngineProtocol.cachePruneRequest("purge", "/c", 0, false, false, null, false);
        assertThat(Jsonl.str(purge, "op")).isEqualTo("purge");
        assertThat(Jsonl.str(purge, "maxSize")).isNull();
    }

    @Test
    void prune_wait_round_trips_pipelines_and_external() {
        String inEngine = EngineProtocol.pruneWait(3, false);
        assertThat(EngineProtocol.typeOf(inEngine)).isEqualTo(EngineProtocol.PRUNE_WAIT);
        assertThat(Jsonl.intValue(inEngine, "pipelines", -1)).isEqualTo(3);
        assertThat(Jsonl.bool(inEngine, "external", true)).isFalse();

        String external = EngineProtocol.pruneWait(0, true);
        assertThat(Jsonl.bool(external, "external", false)).isTrue();
    }

    @Test
    void cache_finish_variant_round_trips_the_summary() {
        String json = EngineProtocol.pipelineFinishCache("", true, 12, 34_567, 2, -1);
        assertThat(EngineProtocol.typeOf(json)).isEqualTo(EngineProtocol.PIPELINE_FINISH);
        assertThat(Jsonl.bool(json, "success", false)).isTrue();
        assertThat(Jsonl.longValue(json, "cacheFiles", -99)).isEqualTo(12);
        assertThat(Jsonl.longValue(json, "cacheBytes", -99)).isEqualTo(34_567);
        assertThat(Jsonl.longValue(json, "cacheReachableEvicted", -99)).isEqualTo(2);
        assertThat(Jsonl.longValue(json, "cacheRepoLinks", -99)).isEqualTo(-1); // n/a for prune
    }
}
