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
}
