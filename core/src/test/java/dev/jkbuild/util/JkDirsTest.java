// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JkDirsTest {

    @Test
    void defaults_to_dot_jk_on_linux() {
        JkDirs dirs = JkDirs.of(Map.<String, String>of()::get, "/home/me");
        assertThat(dirs.homeDir()).isEqualTo(Path.of("/home/me/.jk"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/home/me/.jk/config.toml"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/home/me/.jk/cache"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/home/me/.jk/state"));
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/home/me/.jk/data"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/home/me/.jk/bin"));
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/home/me/.jk/jdks"));
    }

    @Test
    void layout_is_identical_on_macos() {
        JkDirs dirs = JkDirs.of(Map.<String, String>of()::get, "/Users/me");
        assertThat(dirs.homeDir()).isEqualTo(Path.of("/Users/me/.jk"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/Users/me/.jk/config.toml"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/Users/me/.jk/cache"));
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/Users/me/.jk/jdks"));
    }

    @Test
    void layout_is_identical_on_windows() {
        JkDirs dirs = JkDirs.of(Map.<String, String>of()::get, "C:\\Users\\me");
        assertThat(dirs.homeDir().toString()).endsWith(".jk");
        assertThat(dirs.cacheDir().toString()).endsWith(".jk" + java.io.File.separator + "cache");
        assertThat(dirs.jdksDir().toString()).endsWith(".jk" + java.io.File.separator + "jdks");
    }

    @Test
    void jk_home_relocates_the_whole_tree() {
        Map<String, String> env = Map.of("JK_HOME", "/opt/jk");
        JkDirs dirs = JkDirs.of(env::get, "/home/me");
        assertThat(dirs.homeDir()).isEqualTo(Path.of("/opt/jk"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/opt/jk/config.toml"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/opt/jk/cache"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/opt/jk/state"));
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/opt/jk/data"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/opt/jk/bin"));
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/opt/jk/jdks"));
    }

    @Test
    void per_dir_env_vars_win_over_jk_home() {
        // JK_*_DIR is an absolute path; no jk suffix appended.
        Map<String, String> env = Map.of(
                "JK_HOME", "/opt/jk",
                "JK_CONFIG_FILE", "/etc/jk-config.toml",
                "JK_CACHE_DIR", "/var/cache/jk",
                "JK_STATE_DIR", "/var/lib/jk/state",
                "JK_DATA_DIR", "/var/lib/jk/data",
                "JK_BIN_DIR", "/usr/local/bin",
                "JK_JDKS_DIR", "/opt/jdks");
        JkDirs dirs = JkDirs.of(env::get, "/home/me");
        assertThat(dirs.homeDir()).isEqualTo(Path.of("/opt/jk"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/etc/jk-config.toml"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/var/cache/jk"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/var/lib/jk/state"));
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/var/lib/jk/data"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/usr/local/bin"));
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/opt/jdks"));
    }

    @Test
    void blank_env_values_are_ignored() {
        Map<String, String> env = Map.of(
                "JK_HOME", "  ",
                "JK_CACHE_DIR", "");
        JkDirs dirs = JkDirs.of(env::get, "/home/me");
        assertThat(dirs.homeDir()).isEqualTo(Path.of("/home/me/.jk"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/home/me/.jk/cache"));
    }

    @Test
    void xdg_env_vars_are_ignored() {
        // jk owns ~/.jk the way Cargo owns ~/.cargo; XDG_* is not consulted.
        Map<String, String> env = Map.of(
                "XDG_CONFIG_HOME", "/x/config",
                "XDG_CACHE_HOME", "/x/cache",
                "XDG_STATE_HOME", "/x/state",
                "XDG_DATA_HOME", "/x/data",
                "XDG_BIN_HOME", "/x/bin");
        JkDirs dirs = JkDirs.of(env::get, "/home/me");
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/home/me/.jk/config.toml"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/home/me/.jk/cache"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/home/me/.jk/state"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/home/me/.jk/bin"));
    }
}
