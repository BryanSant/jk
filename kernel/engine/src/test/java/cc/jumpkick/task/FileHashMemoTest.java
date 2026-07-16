// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileHashMemoTest {

    /** Run {@code body} with the session cache rooted at {@code cache} (memo isolation). */
    private static void withCache(Path cache, Runnable body) {
        SessionContext.runWhere(Session.defaults().withCacheDir(cache), body);
    }

    @Test
    void roundtrip_for_a_settled_file(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("a.jar"), "AA");
        long mtime = System.currentTimeMillis() - 60_000; // settled long ago
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime));
        long size = Files.size(f);
        withCache(dir.resolve("cache"), () -> {
            assertThat(FileHashMemo.lookup(f, size, mtime)).as("empty memo").isNull();
            FileHashMemo.store(f, size, mtime, "jar:abc");
            assertThat(FileHashMemo.lookup(f, size, mtime)).isEqualTo("jar:abc");
        });
    }

    @Test
    void stat_mismatch_invalidates(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("a.jar"), "AA");
        long mtime = System.currentTimeMillis() - 60_000;
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime));
        long size = Files.size(f);
        withCache(dir.resolve("cache"), () -> {
            FileHashMemo.store(f, size, mtime, "jar:abc");
            assertThat(FileHashMemo.lookup(f, size + 1, mtime)).as("size changed").isNull();
            assertThat(FileHashMemo.lookup(f, size, mtime - 5_000)).as("mtime changed").isNull();
        });
    }

    @Test
    void a_freshly_modified_file_is_never_trusted_or_stored(@TempDir Path dir) throws Exception {
        // Filesystem mtimes are truncated: a file modified "just now" could change
        // again within the same tick without the stat noticing. Within the settle
        // window the memo must stand aside and let content hashing decide.
        Path f = Files.writeString(dir.resolve("a.jar"), "AA");
        long now = System.currentTimeMillis();
        long size = Files.size(f);
        withCache(dir.resolve("cache"), () -> {
            FileHashMemo.store(f, size, now, "jar:abc"); // must be a no-op
            long settled = now - 60_000;
            FileHashMemo.store(f, size, settled, "jar:settled");
            assertThat(FileHashMemo.lookup(f, size, now)).as("fresh mtime — never trusted").isNull();
            assertThat(FileHashMemo.lookup(f, size, settled)).isEqualTo("jar:settled");
        });
    }
}
