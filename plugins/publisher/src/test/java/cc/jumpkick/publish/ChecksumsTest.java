// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ChecksumsTest {

    @Test
    void hex_digests_match_known_values_for_empty_input() {
        byte[] empty = new byte[0];
        Checksums.Set s = Checksums.of(empty);
        // RFC 1321 / FIPS 180-4 well-known digests of the empty string.
        assertThat(s.md5()).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
        assertThat(s.sha1()).isEqualTo("da39a3ee5e6b4b0d3255bfef95601890afd80709");
        assertThat(s.sha256()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(s.sha512()).startsWith("cf83e1357eefb8bdf1542850d66d8007");
    }

    @Test
    void hex_digests_for_abc() {
        byte[] abc = "abc".getBytes(StandardCharsets.US_ASCII);
        Checksums.Set s = Checksums.of(abc);
        assertThat(s.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(s.sha1()).isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    @Test
    void individual_helpers_match_the_set() {
        byte[] data = "hello world\n".getBytes(StandardCharsets.UTF_8);
        Checksums.Set s = Checksums.of(data);
        assertThat(Checksums.md5Hex(data)).isEqualTo(s.md5());
        assertThat(Checksums.sha1Hex(data)).isEqualTo(s.sha1());
        assertThat(Checksums.sha256Hex(data)).isEqualTo(s.sha256());
        assertThat(Checksums.sha512Hex(data)).isEqualTo(s.sha512());
    }
}
