// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JkDirsTest {

    @Test
    void defaults_follow_xdg_on_linux() {
        JkDirs dirs = JkDirs.of(Map.<String, String>of()::get, "/home/me", "Linux");
        assertThat(dirs.configDir()).isEqualTo(Path.of("/home/me/.config/jk"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/home/me/.cache/jk"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/home/me/.local/state/jk"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/home/me/.local/bin"));
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/home/me/.jdks"));
    }

    @Test
    void defaults_follow_xdg_on_macos_except_jdks() {
        // macOS keeps the IntelliJ neighbor location for JDKs; config/cache
        // still use XDG-style paths to match modern CLI conventions.
        JkDirs dirs = JkDirs.of(Map.<String, String>of()::get, "/Users/me", "Mac OS X");
        assertThat(dirs.configDir()).isEqualTo(Path.of("/Users/me/.config/jk"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/Users/me/.cache/jk"));
        assertThat(dirs.jdksDir())
                .isEqualTo(Path.of("/Users/me/Library/Java/JavaVirtualMachines"));
    }

    @Test
    void xdg_env_vars_redirect_the_base() {
        Map<String, String> env = Map.of(
                "XDG_CONFIG_HOME", "/x/config",
                "XDG_CACHE_HOME", "/x/cache",
                "XDG_STATE_HOME", "/x/state",
                "XDG_BIN_HOME", "/x/bin");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.configDir()).isEqualTo(Path.of("/x/config/jk"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/x/cache/jk"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/x/state/jk"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/x/bin"));
    }

    @Test
    void jk_env_vars_win_over_xdg() {
        // JK_*_DIR is the absolute jk dir (not a base), so no `/jk` suffix.
        Map<String, String> env = Map.of(
                "JK_CONFIG_DIR", "/jk/config",
                "JK_CACHE_DIR", "/jk/cache",
                "JK_STATE_DIR", "/jk/state",
                "JK_BIN_DIR", "/jk/bin",
                "JK_JDKS_DIR", "/jk/jdks",
                "XDG_CONFIG_HOME", "/x/config",
                "XDG_CACHE_HOME", "/x/cache");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.configDir()).isEqualTo(Path.of("/jk/config"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/jk/cache"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/jk/state"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/jk/bin"));
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/jk/jdks"));
    }

    @Test
    void blank_env_values_are_ignored() {
        Map<String, String> env = Map.of(
                "JK_CACHE_DIR", "",
                "XDG_CACHE_HOME", "   ");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/home/me/.cache/jk"));
    }
}
