// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DaemonPathsTest {

    @Test
    void same_state_dir_yields_the_same_key() {
        Path a = Path.of("/tmp/jk-home-a/state");
        assertThat(DaemonPaths.keyFor(a)).isEqualTo(DaemonPaths.keyFor(a));
    }

    @Test
    void different_state_dirs_yield_different_keys() {
        Path a = Path.of("/tmp/jk-home-a/state");
        Path b = Path.of("/tmp/jk-home-b/state");
        assertThat(DaemonPaths.keyFor(a)).isNotEqualTo(DaemonPaths.keyFor(b));
    }

    @Test
    void paths_are_all_siblings_under_a_daemon_subdir_named_by_key() {
        Path state = Path.of("/tmp/jk-home/state");
        DaemonPaths.Paths p = DaemonPaths.resolve(state);
        Path daemonDir = state.resolve("daemon");
        assertThat(p.dir()).isEqualTo(daemonDir);
        assertThat(p.socket()).isEqualTo(daemonDir.resolve(p.key() + ".sock"));
        assertThat(p.lock()).isEqualTo(daemonDir.resolve(p.key() + ".lock"));
        assertThat(p.pid()).isEqualTo(daemonDir.resolve(p.key() + ".pid"));
        assertThat(p.log()).isEqualTo(daemonDir.resolve(p.key() + ".log"));
    }
}
