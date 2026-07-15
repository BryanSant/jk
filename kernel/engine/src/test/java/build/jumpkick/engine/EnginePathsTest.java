// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EnginePathsTest {

    @Test
    void same_state_dir_yields_the_same_key() {
        Path a = Path.of("/tmp/jk-home-a/state");
        assertThat(EnginePaths.keyFor(a)).isEqualTo(EnginePaths.keyFor(a));
    }

    @Test
    void different_state_dirs_yield_different_keys() {
        Path a = Path.of("/tmp/jk-home-a/state");
        Path b = Path.of("/tmp/jk-home-b/state");
        assertThat(EnginePaths.keyFor(a)).isNotEqualTo(EnginePaths.keyFor(b));
    }

    @Test
    void paths_are_all_siblings_under_a_engine_subdir_named_by_key() {
        Path state = Path.of("/tmp/jk-home/state");
        EnginePaths.Paths p = EnginePaths.resolve(state);
        Path engineDir = state.resolve("engine");
        assertThat(p.dir()).isEqualTo(engineDir);
        assertThat(p.socket()).isEqualTo(engineDir.resolve(p.key() + ".sock"));
        assertThat(p.lock()).isEqualTo(engineDir.resolve(p.key() + ".lock"));
        assertThat(p.pid()).isEqualTo(engineDir.resolve(p.key() + ".pid"));
        assertThat(p.log()).isEqualTo(engineDir.resolve(p.key() + ".log"));
        assertThat(p.token()).isEqualTo(engineDir.resolve(p.key() + ".token"));
    }

    @Test
    void tokenFor_derives_the_token_sibling_of_a_sock_path_by_naming_convention() {
        Path state = Path.of("/tmp/jk-home/state");
        EnginePaths.Paths p = EnginePaths.resolve(state);
        assertThat(EnginePaths.tokenFor(p.socket())).isEqualTo(p.token());
    }
}
