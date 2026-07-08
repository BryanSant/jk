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
import java.nio.file.attribute.FileTime;
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
    void falls_back_to_cas_when_m2_artifact_no_longer_matches_lock(@TempDir Path tempDir) throws Exception {
        // Point the Maven local repo at a scratch dir (see M2Dirs) for the duration.
        System.setProperty("jk.m2.local", tempDir.resolve("m2").toString());
        try {
            Cas cas = new Cas(tempDir.resolve("cache"));
            byte[] jar = "genuine".getBytes(StandardCharsets.UTF_8);
            String hex = Hashing.sha256Hex(jar);
            String m2Path = "com/foo/a/1.0/a-1.0.jar";
            Path m2Jar = tempDir.resolve("m2").resolve(m2Path);
            Files.createDirectories(m2Jar.getParent());
            Files.write(m2Jar, jar);
            RepoArtifactStore.forRepoName(cas.root(), "central").recordIndex(m2Path, hex);

            Lockfile lock = new Lockfile(
                    Lockfile.CURRENT_VERSION,
                    "jk test",
                    Lockfile.RESOLUTION_ALGORITHM,
                    List.of(pkg("com.foo:a", "1.0", "sha256:" + hex)));

            // Intact mirror: the human-readable ~/.m2 path wins.
            assertThat(new ClasspathResolver(cas).classpathFor(lock)).containsExactly(m2Jar);

            // Poison the ~/.m2 copy out-of-band (newer mtime, different bytes). The
            // resolver must not serve content the lockfile never pinned — it falls
            // back to the CAS path, whose bytes are the hash it is named by.
            Files.write(m2Jar, "poisoned".getBytes(StandardCharsets.UTF_8));
            Files.setLastModifiedTime(m2Jar, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
            assertThat(new ClasspathResolver(cas).classpathFor(lock)).containsExactly(cas.pathFor(hex));
        } finally {
            System.clearProperty("jk.m2.local");
        }
    }

    private static Lockfile.Artifact pkg(String module, String version, String checksum) {
        return new Lockfile.Artifact(
                module, version, "central+https://repo.maven.apache.org/maven2/", checksum, null, List.of());
    }
}
