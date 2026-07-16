// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * repos/local is a publish destination, not a derived cache: a freshly `installLocal`-published
 * artifact is legitimately unreferenced by any action/sync manifest until the first build uses
 * it. Its sidecars must be sweep ROOTS — the prune once deleted every just-installed worker jar
 * (blob swept as unreachable, repo entry removed with it) in exactly that window.
 */
class CacheRootsLocalRepoTest {

    @Test
    void locally_published_artifacts_are_sweep_roots(@TempDir Path cacheRoot) throws IOException {
        Cas cas = new Cas(cacheRoot);
        byte[] jar = "worker jar bytes".getBytes();
        Path blob = cas.put(jar);
        String hex = Hashing.sha256Hex(jar);
        // Published like installLocal does: artifact + .sha256 sidecar under repos/local.
        Path artifact = cacheRoot.resolve("repos/local/cc/jumpkick/jk-test-runner/1.0/jk-test-runner-1.0.jar");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, jar);
        Files.writeString(Path.of(artifact + ".sha256"), hex);
        // Old enough to be sweep-eligible (past the min-age guard).
        Files.setLastModifiedTime(blob, FileTime.fromMillis(System.currentTimeMillis() - 24L * 60 * 60 * 1000));

        Set<String> roots = CacheRoots.collect(cas, cacheRoot.resolve("actions"), cacheRoot.resolve("tools"));
        assertThat(roots).contains(hex);

        var report = CasSweep.sweep(cas, roots, false);
        assertThat(Files.exists(blob)).as("published blob survives the sweep").isTrue();
        assertThat(Files.exists(artifact))
                .as("published jar survives the repo GC")
                .isTrue();
        assertThat(report.deleted()).isZero();
    }

    @Test
    void other_repo_stores_remain_sweepable_mirrors(@TempDir Path cacheRoot) throws IOException {
        Cas cas = new Cas(cacheRoot);
        byte[] dep = "central mirror bytes".getBytes();
        Path blob = cas.put(dep);
        String hex = Hashing.sha256Hex(dep);
        Path artifact = cacheRoot.resolve("repos/central/com/example/widget/1.0/widget-1.0.jar");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, dep);
        Files.writeString(Path.of(artifact + ".sha256"), hex);
        Files.setLastModifiedTime(blob, FileTime.fromMillis(System.currentTimeMillis() - 24L * 60 * 60 * 1000));

        Set<String> roots = CacheRoots.collect(cas, cacheRoot.resolve("actions"), cacheRoot.resolve("tools"));
        assertThat(roots).doesNotContain(hex);

        CasSweep.sweep(cas, roots, false);
        assertThat(Files.exists(blob)).as("unreferenced mirror blob is swept").isFalse();
        assertThat(Files.exists(artifact)).as("mirror entry follows its blob").isFalse();
    }
}
