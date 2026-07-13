// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionStoreTest {

    @Test
    void materialize_is_idempotent_crash_safe_and_cas_backed(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(Files.createDirectories(tmp.resolve("cache")));
        VersionStore store = new VersionStore(tmp.resolve("versions"));
        Path jar = Files.writeString(tmp.resolve("engine.jar"), "engine-bytes");
        Path bin = Files.writeString(tmp.resolve("jk"), "client-bytes");

        var m = store.materializeFromFiles("0.11.0", cas, jar, bin);
        assertThat(m.engineJar()).hasContent("engine-bytes");
        assertThat(m.clientBin()).isPresent();
        assertThat(m.clientBin().get()).hasContent("client-bytes");
        assertThat(m.root().resolve(VersionStore.MANIFEST)).exists();
        // The CAS never shares an inode with the materialized (mutable-adjacent) tree.
        assertThat(Files.isSameFile(m.engineJar(), cas.pathFor(dev.jkbuild.util.Hashing.sha256Hex(jar))))
                .isFalse();

        // Idempotent: a second materialization returns the existing dir untouched.
        var again = store.materializeFromFiles("0.11.0", cas, jar, bin);
        assertThat(again.root()).isEqualTo(m.root());

        // A manifest-less dir is an aborted materialization: invisible to readers.
        Path aborted = Files.createDirectories(store.versionsDir().resolve("0.12.0/lib"));
        Files.writeString(aborted.resolve("jk-engine.jar"), "half");
        assertThat(store.resolve("0.12.0")).isEmpty();
    }

    @Test
    void newest_orders_versions_with_qualifiers_below_releases(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(Files.createDirectories(tmp.resolve("cache")));
        VersionStore store = new VersionStore(tmp.resolve("versions"));
        Path jar = Files.writeString(tmp.resolve("engine.jar"), "e");
        for (String v : new String[] {"0.9.2", "0.10.0", "0.11.0-SNAPSHOT"}) {
            store.materializeFromFiles(v, cas, jar, null);
        }
        assertThat(store.newest().orElseThrow().version()).isEqualTo("0.11.0-SNAPSHOT");
        store.materializeFromFiles("0.11.0", cas, jar, null);
        assertThat(store.newest().orElseThrow().version()).isEqualTo("0.11.0");

        assertThat(VersionStore.compare("0.10.0", "0.9.9")).isPositive();
        assertThat(VersionStore.compare("1.0.0-SNAPSHOT", "1.0.0")).isNegative();
        assertThat(VersionStore.compare("1.0.0", "1.0")).isZero();
    }
}
