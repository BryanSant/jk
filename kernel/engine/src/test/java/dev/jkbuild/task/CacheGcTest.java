// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.repo.JkMavenLocalRepo;
import dev.jkbuild.repo.MavenLayout;
import dev.jkbuild.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CacheGcTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    private static Path seed(Path cache, String body, Coordinate coord) throws IOException {
        Cas cas = new Cas(cache);
        Path blob = cas.put(body.getBytes(StandardCharsets.UTF_8));
        new JkMavenLocalRepo(cache).materialize(MavenLayout.artifactPath(coord), blob);
        return blob;
    }

    @Test
    void purges_unreachable_blob_idle_past_90_days(@TempDir Path cache) throws IOException {
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
        seed(cache, "stale", coord);
        String hex = Hashing.sha256Hex("stale".getBytes(StandardCharsets.UTF_8));
        long old = System.currentTimeMillis() - 100 * DAY;
        Files.writeString(cache.resolve(".access.log"), hex + "\t" + old + "\t1\n");

        CacheGc.Report report = CacheGc.run(cache, false);

        assertThat(report.purgedBlobs()).isEqualTo(1);
        assertThat(report.repoLinksRemoved()).isEqualTo(1);
        assertThat(new Cas(cache).contains(hex)).isFalse();
        assertThat(cache.resolve("repo/com/example/widget/1.0/widget-1.0.jar")).doesNotExist();
        // The purged sha's entry is gone from the access log.
        assertThat(Files.readString(cache.resolve(".access.log"))).doesNotContain(hex);
    }

    @Test
    void keeps_reachable_blob_however_old(@TempDir Path cache) throws IOException {
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
        seed(cache, "live", coord);
        String hex = Hashing.sha256Hex("live".getBytes(StandardCharsets.UTF_8));
        // Mark it reachable from an action record.
        Files.createDirectories(cache.resolve("actions/keys"));
        Files.writeString(cache.resolve("actions/keys/k1"), "OUTPUT " + hex + "\n");
        long old = System.currentTimeMillis() - 365 * DAY;
        Files.writeString(cache.resolve(".access.log"), hex + "\t" + old + "\t1\n");

        CacheGc.Report report = CacheGc.run(cache, false);

        assertThat(report.purgedBlobs()).isZero();
        assertThat(new Cas(cache).contains(hex)).isTrue();
    }

    @Test
    void keeps_unreachable_but_recently_accessed_blob(@TempDir Path cache) throws IOException {
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
        seed(cache, "warm", coord);
        String hex = Hashing.sha256Hex("warm".getBytes(StandardCharsets.UTF_8));
        long recent = System.currentTimeMillis() - 3 * DAY;
        Files.writeString(cache.resolve(".access.log"), hex + "\t" + recent + "\t1\n");

        CacheGc.Report report = CacheGc.run(cache, false);

        assertThat(report.purgedBlobs()).isZero();
        assertThat(new Cas(cache).contains(hex)).isTrue();
    }

    @Test
    void access_log_is_summed_deduped_and_rewritten(@TempDir Path cache) throws IOException {
        // A kept (recent, unreachable) blob with two loose log lines.
        seed(cache, "keep", Coordinate.of("com.example", "widget", "1.0"));
        String hex = Hashing.sha256Hex("keep".getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        Files.writeString(cache.resolve(".access.log"),
                hex + "\t" + (now - 1000) + "\t1\n" + hex + "\t" + now + "\t1\n");

        CacheGc.run(cache, false);

        String[] lines = Files.readString(cache.resolve(".access.log")).strip().split("\n");
        assertThat(lines).hasSize(1);
        // <hex>\t<latest-millis>\t<summed-count>
        assertThat(lines[0]).isEqualTo(hex + "\t" + now + "\t2");
    }

    @Test
    void dry_run_touches_nothing(@TempDir Path cache) throws IOException {
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
        seed(cache, "stale", coord);
        String hex = Hashing.sha256Hex("stale".getBytes(StandardCharsets.UTF_8));
        long old = System.currentTimeMillis() - 100 * DAY;
        Files.writeString(cache.resolve(".access.log"), hex + "\t" + old + "\t1\n");

        CacheGc.Report report = CacheGc.run(cache, true);

        assertThat(report.purgedBlobs()).isEqualTo(1);
        assertThat(new Cas(cache).contains(hex)).isTrue(); // not actually deleted
        assertThat(cache.resolve("repo/com/example/widget/1.0/widget-1.0.jar")).exists();
    }
}
