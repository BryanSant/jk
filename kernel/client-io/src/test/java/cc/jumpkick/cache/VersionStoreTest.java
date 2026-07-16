// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

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
        assertThat(Files.isSameFile(m.engineJar(), cas.pathFor(cc.jumpkick.util.Hashing.sha256Hex(jar))))
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
    /**
     * Same version, different bytes — a dev -SNAPSHOT re-install — must REPLACE the tree.
     * The short-circuit once kept a stale engine jar in place while a freshly-built client
     * believed it had installed itself (new client speaking to an old-protocol engine).
     */
    @Test
    void rematerializing_the_same_version_with_new_bytes_replaces_the_tree(@TempDir Path dir) throws Exception {
        var cas = new cc.jumpkick.cache.Cas(dir.resolve("cache"));
        var store = new VersionStore(dir.resolve("versions"));
        Path jarV1 = dir.resolve("engine-v1.jar");
        Files.writeString(jarV1, "engine bytes v1");
        Path jarV2 = dir.resolve("engine-v2.jar");
        Files.writeString(jarV2, "engine bytes v2 (rebuilt snapshot)");

        var first = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV1, null);
        assertThat(first.engineJar()).hasContent("engine bytes v1");

        var second = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV2, null);
        assertThat(second.engineJar()).hasContent("engine bytes v2 (rebuilt snapshot)");

        // And identical bytes short-circuit (release immutability fast path).
        var third = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV2, null);
        assertThat(third.root()).isEqualTo(second.root());
    }

    @Test
    void prune_retires_a_stale_version_with_its_engine_aot_caches(@TempDir Path home) throws Exception {
        VersionStore store = new VersionStore(home.resolve("versions"));
        java.nio.file.Path stale =
                java.nio.file.Files.createDirectories(home.resolve("versions").resolve("0.9.0"));
        java.nio.file.Files.writeString(stale.resolve("manifest.toml"), "");
        java.nio.file.Path state = java.nio.file.Files.createDirectories(home.resolve("state"));
        java.nio.file.Path legacy =
                java.nio.file.Files.createDirectories(state.resolve("engine").resolve("0.9.0"));
        java.nio.file.Path aot = java.nio.file.Files.createDirectories(state.resolve("aot"));
        java.nio.file.Path staleCache =
                java.nio.file.Files.writeString(aot.resolve("engine-0.9.0-aaaaaaaaaaaaaaaa.aot"), "x");
        java.nio.file.Path staleMarker =
                java.nio.file.Files.writeString(aot.resolve("engine-0.9.0-aaaaaaaaaaaaaaaa.aot.noaot"), "");
        // A version whose name merely EXTENDS the pruned one must survive its sweep.
        java.nio.file.Path lookalike =
                java.nio.file.Files.writeString(aot.resolve("engine-0.9.0-SNAPSHOT-bbbbbbbbbbbbbbbb.aot"), "y");
        java.nio.file.Path workerCache =
                java.nio.file.Files.writeString(aot.resolve("javac-cccccccccccccccc.aot"), "z");

        java.util.List<String> pruned = store.prune("1.0.0", java.time.Duration.ofDays(30), key -> 0L, state);

        org.assertj.core.api.Assertions.assertThat(pruned).containsExactly("0.9.0");
        org.assertj.core.api.Assertions.assertThat(stale).doesNotExist();
        org.assertj.core.api.Assertions.assertThat(legacy).doesNotExist();
        org.assertj.core.api.Assertions.assertThat(staleCache).doesNotExist();
        org.assertj.core.api.Assertions.assertThat(staleMarker).doesNotExist();
        org.assertj.core.api.Assertions.assertThat(lookalike).exists();
        org.assertj.core.api.Assertions.assertThat(workerCache).exists();
    }
}
