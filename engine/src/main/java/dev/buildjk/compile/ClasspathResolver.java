// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compile;

import dev.buildjk.cache.Cas;
import dev.buildjk.lock.Lockfile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maps a {@link Lockfile}'s checksummed packages to on-disk artifact
 * paths in the {@link Cas}. Packages without a checksum
 * (POM-only / path / git) are skipped — they don't contribute to the
 * compile classpath.
 *
 * <p>This is a pure name-resolution step: it doesn't fetch anything.
 * {@code jk sync} / {@code jk fetch} are what ensure the CAS is
 * populated before this runs.
 */
public final class ClasspathResolver {

    private final Cas cas;

    public ClasspathResolver(Cas cas) {
        this.cas = Objects.requireNonNull(cas, "cas");
    }

    public List<Path> classpathFor(Lockfile lock) {
        List<Path> result = new ArrayList<>(lock.packages().size());
        for (Lockfile.Package pkg : lock.packages()) {
            String checksum = pkg.checksum();
            if (checksum == null) continue;
            String hex = checksum.startsWith("sha256:")
                    ? checksum.substring("sha256:".length())
                    : checksum;
            result.add(cas.pathFor(hex));
        }
        return result;
    }
}
