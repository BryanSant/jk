// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The wrapper scripts are FROZEN (engine-versioning-plan §7): they may depend on exactly two
 * surfaces — the release URL layout (releases.md) and the lock's one-line toolchain pin — and
 * nothing else about jk. This test pins that contract so a template edit that reaches deeper
 * fails loudly.
 */
class WrapperTemplateTest {

    private static String template(String name) throws Exception {
        try (InputStream in = WrapperCommand.class.getResourceAsStream("wrapper/" + name)) {
            assertThat(in).as("template " + name + " bundled in the jar").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void posix_wrapper_touches_only_the_frozen_surfaces() throws Exception {
        String sh = template("jk.sh");
        // The two frozen dependencies…
        assertThat(sh).contains("jk.lock").contains("\"jk = \"*").contains("latest/VERSION");
        assertThat(sh).contains("versions/$VERSION/bin/jk");
        // …and the sha pin gates the download.
        assertThat(sh).contains("sha256");
        // Nothing daemon-shaped: the wrapper needs zero engine/endpoint awareness. (The word
        // "engine" itself appears in the doc-reference comment — assert on the mechanisms.)
        assertThat(sh).doesNotContain(".sock").doesNotContain("endpoint").doesNotContain("gen1");
    }

    @Test
    void windows_wrapper_touches_only_the_frozen_surfaces() throws Exception {
        String bat = template("jk.bat");
        assertThat(bat).contains("jk.lock").contains("latest/VERSION");
        assertThat(bat).contains("versions\\%VERSION%\\bin");
        assertThat(bat).contains("SHA256");
        assertThat(bat).doesNotContain(".sock").doesNotContain("endpoint");
    }
}
