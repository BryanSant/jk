// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli.tui;

import org.jline.utils.NonBlocking;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KeyReaderTest {

    private static NonBlockingReader reader(byte[] bytes) {
        var src = new String(bytes, StandardCharsets.ISO_8859_1);
        return NonBlocking.nonBlocking("test", new StringReader(src));
    }

    @Test
    void ctrl_c_maps_to_ctrlc() {
        var key = KeyReader.read(reader(new byte[] {0x03}));
        assertThat(key).isInstanceOf(KeyReader.Key.CtrlC.class);
    }

    @Test
    void lf_maps_to_enter() {
        var key = KeyReader.read(reader(new byte[] {0x0A}));
        assertThat(key).isInstanceOf(KeyReader.Key.Enter.class);
    }

    @Test
    void cr_maps_to_enter() {
        var key = KeyReader.read(reader(new byte[] {0x0D}));
        assertThat(key).isInstanceOf(KeyReader.Key.Enter.class);
    }

    @Test
    void space_maps_to_space() {
        var key = KeyReader.read(reader(new byte[] {0x20}));
        assertThat(key).isInstanceOf(KeyReader.Key.Space.class);
    }

    @Test
    void del_maps_to_backspace() {
        var key = KeyReader.read(reader(new byte[] {0x7F}));
        assertThat(key).isInstanceOf(KeyReader.Key.Backspace.class);
    }

    @Test
    void bs_maps_to_backspace() {
        var key = KeyReader.read(reader(new byte[] {0x08}));
        assertThat(key).isInstanceOf(KeyReader.Key.Backspace.class);
    }

    @Test
    void csi_a_maps_to_up() {
        var key = KeyReader.read(reader(new byte[] {0x1B, '[', 'A'}));
        assertThat(key).isInstanceOf(KeyReader.Key.Up.class);
    }

    @Test
    void csi_b_maps_to_down() {
        var key = KeyReader.read(reader(new byte[] {0x1B, '[', 'B'}));
        assertThat(key).isInstanceOf(KeyReader.Key.Down.class);
    }

    @Test
    void csi_c_maps_to_right() {
        var key = KeyReader.read(reader(new byte[] {0x1B, '[', 'C'}));
        assertThat(key).isInstanceOf(KeyReader.Key.Right.class);
    }

    @Test
    void csi_d_maps_to_left() {
        var key = KeyReader.read(reader(new byte[] {0x1B, '[', 'D'}));
        assertThat(key).isInstanceOf(KeyReader.Key.Left.class);
    }

    @Test
    void bare_escape_with_no_followup_maps_to_escape() {
        var key = KeyReader.read(reader(new byte[] {0x1B}));
        assertThat(key).isInstanceOf(KeyReader.Key.Escape.class);
    }

    @Test
    void printable_ascii_maps_to_char() {
        var key = KeyReader.read(reader(new byte[] {0x61}));
        assertThat(key).isInstanceOfSatisfying(KeyReader.Key.Char.class, c -> assertThat(c.c()).isEqualTo('a'));
    }

    @Test
    void printable_z_maps_to_char() {
        var key = KeyReader.read(reader(new byte[] {'Z'}));
        assertThat(key).isInstanceOfSatisfying(KeyReader.Key.Char.class, c -> assertThat(c.c()).isEqualTo('Z'));
    }
}
