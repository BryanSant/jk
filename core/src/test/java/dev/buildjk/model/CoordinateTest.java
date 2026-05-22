// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoordinateTest {

    @Test
    void parses_gav() {
        Coordinate c = Coordinate.parse("com.fasterxml.jackson.core:jackson-databind:2.18.2");
        assertThat(c.group()).isEqualTo("com.fasterxml.jackson.core");
        assertThat(c.artifact()).isEqualTo("jackson-databind");
        assertThat(c.version()).isEqualTo("2.18.2");
        assertThat(c.classifier()).isNull();
        assertThat(c.type()).isEqualTo("jar");
        assertThat(c.toString()).isEqualTo("com.fasterxml.jackson.core:jackson-databind:2.18.2");
    }

    @Test
    void parses_gav_with_classifier_and_type() {
        Coordinate c = Coordinate.parse("io.netty:netty-transport-native-epoll:4.1.115:linux-x86_64@jar");
        assertThat(c.classifier()).isEqualTo("linux-x86_64");
        assertThat(c.type()).isEqualTo("jar");
    }

    @Test
    void module_strips_version() {
        Coordinate c = Coordinate.of("com.foo", "bar", "1.0");
        assertThat(c.module()).isEqualTo("com.foo:bar");
    }

    @Test
    void rejects_malformed() {
        assertThatThrownBy(() -> Coordinate.parse("foo:bar"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
