// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HostCheckTest {

    private static final int PORT = 8910;

    @Test
    void ip_literals_are_allowed() {
        assertThat(HostCheck.allowed("127.0.0.1", PORT)).isTrue();
        assertThat(HostCheck.allowed("127.0.0.1:8910", PORT)).isTrue();
        assertThat(HostCheck.allowed("192.168.1.5:8910", PORT)).isTrue(); // LAN bind, addressed by IP
        assertThat(HostCheck.allowed("[::1]:8910", PORT)).isTrue();
        assertThat(HostCheck.allowed("[::1]", PORT)).isTrue();
    }

    @Test
    void localhost_is_allowed() {
        assertThat(HostCheck.allowed("localhost", PORT)).isTrue();
        assertThat(HostCheck.allowed("localhost:8910", PORT)).isTrue();
        assertThat(HostCheck.allowed("LOCALHOST:8910", PORT)).isTrue();
    }

    @Test
    void dns_names_are_rejected() {
        // The rebinding vector: a name the attacker controls, resolved to this machine.
        assertThat(HostCheck.allowed("evil.example.com", PORT)).isFalse();
        assertThat(HostCheck.allowed("evil.example.com:8910", PORT)).isFalse();
        assertThat(HostCheck.allowed("localhost.evil.example.com:8910", PORT)).isFalse();
    }

    @Test
    void wrong_port_is_rejected() {
        assertThat(HostCheck.allowed("127.0.0.1:1", PORT)).isFalse();
        assertThat(HostCheck.allowed("localhost:1", PORT)).isFalse();
        assertThat(HostCheck.allowed("[::1]:1", PORT)).isFalse();
        assertThat(HostCheck.allowed("127.0.0.1:", PORT)).isFalse();
        assertThat(HostCheck.allowed("127.0.0.1:x", PORT)).isFalse();
    }

    @Test
    void missing_or_blank_host_is_rejected() {
        assertThat(HostCheck.allowed(null, PORT)).isFalse();
        assertThat(HostCheck.allowed("", PORT)).isFalse();
        assertThat(HostCheck.allowed("   ", PORT)).isFalse();
    }

    @Test
    void almost_ip_literals_are_rejected() {
        assertThat(HostCheck.allowed("127.0.0.256", PORT)).isFalse(); // out-of-range octet = a name
        assertThat(HostCheck.allowed("127.0.0", PORT)).isFalse();
        assertThat(HostCheck.allowed("127.0.0.1.evil.example.com", PORT)).isFalse();
        assertThat(HostCheck.allowed("[not-an-ip]", PORT)).isFalse();
        assertThat(HostCheck.allowed("[::1", PORT)).isFalse(); // unclosed bracket
    }
}
