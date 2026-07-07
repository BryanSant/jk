// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.daemon.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.plugin.protocol.Ndjson;
import org.junit.jupiter.api.Test;

class DaemonProtocolTest {

    @Test
    void hello_round_trips_the_version() {
        String json = DaemonProtocol.hello("1.2.3");
        assertThat(DaemonProtocol.typeOf(json)).isEqualTo(DaemonProtocol.HELLO);
        assertThat(Ndjson.str(json, "version")).isEqualTo("1.2.3");
    }

    @Test
    void hello_ack_round_trips_version_pid_and_start_time() {
        String json = DaemonProtocol.helloAck("1.2.3", 4321, 999_000);
        assertThat(DaemonProtocol.typeOf(json)).isEqualTo(DaemonProtocol.HELLO_ACK);
        assertThat(Ndjson.str(json, "version")).isEqualTo("1.2.3");
        assertThat(Ndjson.longValue(json, "pid", -1)).isEqualTo(4321);
        assertThat(Ndjson.longValue(json, "startedAt", -1)).isEqualTo(999_000);
    }

    @Test
    void ping_and_pong_are_distinct_types() {
        assertThat(DaemonProtocol.typeOf(DaemonProtocol.ping())).isEqualTo(DaemonProtocol.PING);
        assertThat(DaemonProtocol.typeOf(DaemonProtocol.pong())).isEqualTo(DaemonProtocol.PONG);
    }

    @Test
    void status_ack_round_trips_all_fields() {
        String json = DaemonProtocol.statusAck("1.2.3", 42, 1_000, 120, 3);
        assertThat(DaemonProtocol.typeOf(json)).isEqualTo(DaemonProtocol.STATUS_ACK);
        assertThat(Ndjson.str(json, "version")).isEqualTo("1.2.3");
        assertThat(Ndjson.longValue(json, "pid", -1)).isEqualTo(42);
        assertThat(Ndjson.longValue(json, "startedAt", -1)).isEqualTo(1_000);
        assertThat(Ndjson.intValue(json, "idleMinutes", -99)).isEqualTo(120);
        assertThat(Ndjson.intValue(json, "activeRequests", -99)).isEqualTo(3);
    }

    @Test
    void shutdown_and_bye_are_distinct_types() {
        assertThat(DaemonProtocol.typeOf(DaemonProtocol.shutdown())).isEqualTo(DaemonProtocol.SHUTDOWN);
        assertThat(DaemonProtocol.typeOf(DaemonProtocol.bye())).isEqualTo(DaemonProtocol.BYE);
    }

    @Test
    void type_of_malformed_json_is_null() {
        assertThat(DaemonProtocol.typeOf("not json")).isNull();
    }
}
