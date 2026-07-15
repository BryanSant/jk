// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.config.JkCacheConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachePruneSchedulerTest {

    @Test
    void should_run_when_stamp_missing(@TempDir Path cacheRoot) throws IOException {
        JkCacheConfig cfg = new JkCacheConfig(true, Optional.empty(), 7, 30);
        assertThat(CachePruneScheduler.shouldRun(cfg, cacheRoot)).isTrue();
    }

    @Test
    void should_not_run_within_interval(@TempDir Path cacheRoot) throws IOException {
        JkCacheConfig cfg = new JkCacheConfig(true, Optional.empty(), 7, 30);
        // Stamp was 1 day ago — interval is 7 → skip.
        long oneDayAgo = System.currentTimeMillis() - (24L * 60 * 60 * 1000);
        Files.writeString(
                cacheRoot.resolve(CachePruneScheduler.LAST_PRUNED_FILE),
                Long.toString(oneDayAgo),
                StandardCharsets.UTF_8);

        assertThat(CachePruneScheduler.shouldRun(cfg, cacheRoot)).isFalse();
    }

    @Test
    void should_run_after_interval(@TempDir Path cacheRoot) throws IOException {
        JkCacheConfig cfg = new JkCacheConfig(true, Optional.empty(), 7, 30);
        long tenDaysAgo = System.currentTimeMillis() - (10L * 24 * 60 * 60 * 1000);
        Files.writeString(
                cacheRoot.resolve(CachePruneScheduler.LAST_PRUNED_FILE),
                Long.toString(tenDaysAgo),
                StandardCharsets.UTF_8);

        assertThat(CachePruneScheduler.shouldRun(cfg, cacheRoot)).isTrue();
    }

    @Test
    void should_run_when_stamp_is_garbage(@TempDir Path cacheRoot) throws IOException {
        JkCacheConfig cfg = new JkCacheConfig(true, Optional.empty(), 7, 30);
        Files.writeString(
                cacheRoot.resolve(CachePruneScheduler.LAST_PRUNED_FILE), "not-a-number", StandardCharsets.UTF_8);
        assertThat(CachePruneScheduler.shouldRun(cfg, cacheRoot)).isTrue();
    }

    @Test
    void command_includes_sweep_and_optional_max_size(@TempDir Path cacheRoot) {
        JkCacheConfig withCap = new JkCacheConfig(true, Optional.of(20), 7, 30);
        var cmd = CachePruneScheduler.commandFor(withCap, cacheRoot, "/usr/local/bin/jk");
        assertThat(cmd).contains("--sweep", "--background", "--max-size", "20G");

        JkCacheConfig noCap = new JkCacheConfig(true, Optional.empty(), 7, 30);
        var cmdNoCap = CachePruneScheduler.commandFor(noCap, cacheRoot, "/usr/local/bin/jk");
        assertThat(cmdNoCap).contains("--sweep", "--background");
        assertThat(cmdNoCap).doesNotContain("--max-size");
    }

    @Test
    void maybe_run_no_op_when_auto_prune_off(@TempDir Path cacheRoot) {
        JkCacheConfig off = new JkCacheConfig(false, Optional.of(20), 7, 30);
        // No exception, no spawn. We can't easily assert "no subprocess
        // started" portably; this test asserts the method returns without
        // raising under the disabled-config path.
        CachePruneScheduler.maybeRun(off, cacheRoot, "/usr/local/bin/jk");
    }
}
