// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HookCommandTest {

    @Test
    void posix_snippet_exports_java_home_and_path() {
        String s = HookCommand.posixSnippet(Path.of("/home/u/.local/share/jk/default-jdk"));
        assertThat(s).contains("# Added by `jk hook`");
        assertThat(s).contains("if [ -L \"/home/u/.local/share/jk/default-jdk\" ]");
        assertThat(s).contains("export JAVA_HOME=\"/home/u/.local/share/jk/default-jdk\"");
        assertThat(s).contains("export PATH=\"$JAVA_HOME/bin:$PATH\"");
    }

    @Test
    void fish_snippet_uses_fish_syntax() {
        String s = HookCommand.fishSnippet(Path.of("/home/u/.local/share/jk/default-jdk"));
        assertThat(s).contains("# Added by `jk hook`");
        assertThat(s).contains("if test -L \"/home/u/.local/share/jk/default-jdk\" -o "
                + "-d \"/home/u/.local/share/jk/default-jdk\"");
        assertThat(s).contains("set -gx JAVA_HOME \"/home/u/.local/share/jk/default-jdk\"");
        assertThat(s).contains("fish_add_path \"$JAVA_HOME/bin\"");
        assertThat(s).contains("end\n");
    }
}
