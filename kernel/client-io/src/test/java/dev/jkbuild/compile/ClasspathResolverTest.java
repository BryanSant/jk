// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.repo.RepoArtifactStore;
import dev.jkbuild.util.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathResolverTest {

    @Test
    void maps_packages_with_checksums_to_cas_paths(@TempDir Path tempDir) {
        Cas cas = new Cas(tempDir);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(pkg("com.foo:a", "1.0", "sha256:aaaa1111"), pkg("com.foo:b", "1.0", "sha256:bbbb2222")));

        List<Path> cp = new ClasspathResolver(cas).classpathFor(lock);
        assertThat(cp).containsExactly(cas.pathFor("aaaa1111"), cas.pathFor("bbbb2222"));
    }

    @Test
    void skips_packages_without_checksum(@TempDir Path tempDir) {
        Cas cas = new Cas(tempDir);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(pkg("com.foo:a", "1.0", "sha256:aaaa1111"), pkg("com.foo:b", "1.0", null)));

        List<Path> cp = new ClasspathResolver(cas).classpathFor(lock);
        assertThat(cp).containsExactly(cas.pathFor("aaaa1111"));
    }

    @Test
    void accepts_raw_hex_checksum(@TempDir Path tempDir) {
        // Some packages may record the bare hex without the "sha256:" prefix.
        Cas cas = new Cas(tempDir);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(pkg("com.foo:a", "1.0", "abcd1234")));

        List<Path> cp = new ClasspathResolver(cas).classpathFor(lock);
        assertThat(cp).containsExactly(cas.pathFor("abcd1234"));
    }

    @Test
    void falls_back_to_cas_when_repos_artifact_no_longer_matches_lock(@TempDir Path tempDir) throws Exception {
        Cas cas = new Cas(tempDir.resolve("cache"));
        byte[] jar = "genuine".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        String m2Path = "com/foo/a/1.0/a-1.0.jar";
        Path casBlob = cas.put(jar);
        RepoArtifactStore store = RepoArtifactStore.forRepoName(cas.root(), "central");
        store.materialize(m2Path, casBlob, hex);
        Path readablePath = store.locate(m2Path).orElseThrow();

        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(pkg("com.foo:a", "1.0", "sha256:" + hex)));

        // Intact store: the human-readable repos/<name>/... path wins.
        assertThat(new ClasspathResolver(cas).classpathFor(lock)).containsExactly(readablePath);

        // Corrupt the index sidecar itself (repos/<name>/ is exclusively jk-owned, so this models
        // local corruption/tampering rather than an external tool's rewrite). The resolver must
        // not serve an artifact whose recorded hash no longer matches the lockfile pin — it falls
        // back to the CAS path, whose bytes are the hash it is named by.
        Path sidecar = store.root().resolve(m2Path + ".sha256");
        Files.writeString(sidecar, "0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(new ClasspathResolver(cas).classpathFor(lock)).containsExactly(cas.pathFor(hex));
    }

    private static Lockfile.Artifact pkg(String module, String version, String checksum) {
        return new Lockfile.Artifact(
                module, version, "central+https://repo.maven.apache.org/maven2/", checksum, null, List.of());
    }
}
