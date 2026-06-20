// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code [global].nerdfont} reading from ~/.jk/config.toml, leniently. */
class GlobalConfigTest {

    @Test
    void reads_global_nerdfont_true(@TempDir Path dir) throws IOException {
        Path cfg = write(dir, """
                [global]
                nerdfont = true
                """);
        assertThat(GlobalConfig.nerdfont(cfg)).isTrue();
    }

    @Test
    void defaults_false_when_unset_or_missing(@TempDir Path dir) throws IOException {
        assertThat(GlobalConfig.nerdfont(dir.resolve("nope.toml"))).isFalse();      // no file
        assertThat(GlobalConfig.nerdfont(write(dir, "[global]\n"))).isFalse();       // table, no key
        assertThat(GlobalConfig.nerdfont(write(dir, "[cache]\nauto-prune = true\n"))).isFalse(); // no [global]
        assertThat(GlobalConfig.nerdfont(write(dir, "[global]\nnerdfont = \"yes\"\n"))).isFalse(); // wrong type
    }

    private static Path write(Path dir, String content) throws IOException {
        Path f = Files.createTempFile(dir, "config", ".toml");
        Files.writeString(f, content);
        return f;
    }
}
