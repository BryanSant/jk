// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.lock.Lockfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathResolverTest {

    @Test
    void maps_packages_with_checksums_to_cas_paths(@TempDir Path tempDir) {
        Cas cas = new Cas(tempDir);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        pkg("com.foo:a", "1.0", "sha256:aaaa1111"),
                        pkg("com.foo:b", "1.0", "sha256:bbbb2222")));

        List<Path> cp = new ClasspathResolver(cas).classpathFor(lock);
        assertThat(cp).containsExactly(
                cas.pathFor("aaaa1111"),
                cas.pathFor("bbbb2222"));
    }

    @Test
    void skips_packages_without_checksum(@TempDir Path tempDir) {
        Cas cas = new Cas(tempDir);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        pkg("com.foo:a", "1.0", "sha256:aaaa1111"),
                        pkg("com.foo:b", "1.0", null)));

        List<Path> cp = new ClasspathResolver(cas).classpathFor(lock);
        assertThat(cp).containsExactly(cas.pathFor("aaaa1111"));
    }

    @Test
    void accepts_raw_hex_checksum(@TempDir Path tempDir) {
        // Some packages may record the bare hex without the "sha256:" prefix.
        Cas cas = new Cas(tempDir);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM,
                List.of(pkg("com.foo:a", "1.0", "abcd1234")));

        List<Path> cp = new ClasspathResolver(cas).classpathFor(lock);
        assertThat(cp).containsExactly(cas.pathFor("abcd1234"));
    }

    private static Lockfile.Artifact pkg(String module, String version, String checksum) {
        return new Lockfile.Artifact(module, version,
                "central+https://repo.maven.apache.org/maven2/",
                checksum, null, List.of());
    }
}
