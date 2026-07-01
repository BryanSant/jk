// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link JkMavenLocalRepo} is now GC-only — it sweeps hard links left under the legacy
 * {@code <cache>/repo/} mirror by jk versions that predate {@link RepoArtifactStore}. Nothing writes
 * to that tree anymore, so these tests seed it directly instead of going through a materialize API.
 */
class JkMavenLocalRepoTest {

    private static Path seed(Path cache, String relativePath, byte[] bytes) throws IOException {
        Path file = cache.resolve("repo").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return file;
    }

    @Test
    void index_by_sha_groups_files_by_content_hash(@TempDir Path cache) throws IOException {
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        Path jar = seed(cache, "com/example/widget/1.0/widget-1.0.jar", bytes);
        String hex = Hashing.sha256Hex(bytes);

        JkMavenLocalRepo repo = new JkMavenLocalRepo(cache);

        assertThat(repo.indexBySha()).containsEntry(hex, java.util.List.of(jar));
    }

    @Test
    void index_by_sha_of_missing_repo_is_empty(@TempDir Path cache) {
        assertThat(new JkMavenLocalRepo(cache).indexBySha()).isEmpty();
    }

    @Test
    void remove_shas_unlinks_matching_files_and_prunes_dirs(@TempDir Path cache) throws IOException {
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        Path jar = seed(cache, "com/example/widget/1.0/widget-1.0.jar", bytes);
        String hex = Hashing.sha256Hex(bytes);
        JkMavenLocalRepo repo = new JkMavenLocalRepo(cache);

        int removed = repo.removeShas(Set.of(hex), false);

        assertThat(removed).isEqualTo(1);
        assertThat(jar).doesNotExist();
        // Empty version/artifact/group dirs are pruned away.
        assertThat(cache.resolve("repo/com")).doesNotExist();
    }

    @Test
    void remove_shas_dry_run_keeps_files(@TempDir Path cache) throws IOException {
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        Path jar = seed(cache, "com/example/widget/1.0/widget-1.0.jar", bytes);
        JkMavenLocalRepo repo = new JkMavenLocalRepo(cache);

        int removed = repo.removeShas(Set.of(Hashing.sha256Hex(bytes)), true);

        assertThat(removed).isEqualTo(1);
        assertThat(jar).exists();
    }

    @Test
    void remove_shas_of_missing_repo_is_a_no_op(@TempDir Path cache) {
        assertThat(new JkMavenLocalRepo(cache).removeShas(Set.of("abc"), false)).isZero();
    }
}
