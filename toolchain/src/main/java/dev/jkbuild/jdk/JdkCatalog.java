// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Parsed view of the JetBrains JDK feed at
 * {@code https://download.jetbrains.com/jdk/feed/v1/jdks.json.xz}. The
 * feed is the same source IntelliJ uses to populate
 * {@code ~/.jdks} (Linux/Windows) and
 * {@code ~/Library/Java/JavaVirtualMachines} (macOS), so jk-installed
 * JDKs naturally appear alongside IntelliJ-installed ones.
 *
 * <p>Each {@link Entry} is a flattened (top-level × package) row: one
 * downloadable archive on one (os, arch) pair. The feed currently puts a
 * single package under each top-level entry, but we treat the structure
 * as 1:N so we don't have to rework parsing if that changes.
 */
public record JdkCatalog(List<Entry> entries) {

    public JdkCatalog {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /**
     * One downloadable JDK on one platform. All identifier fields come
     * straight from the feed — there is no jk-side naming logic.
     */
    public record Entry(
            String vendor,
            String product,
            String suggestedSdkName,
            int majorVersion,
            String version,
            boolean defaultForMajor,
            boolean preview,
            List<String> aliases,
            String os,
            String arch,
            String packageType,
            URI url,
            String sha256,
            long archiveSize,
            String installFolderName,
            String javaHomeSubpath) {

        public Entry {
            Objects.requireNonNull(vendor, "vendor");
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(suggestedSdkName, "suggestedSdkName");
            Objects.requireNonNull(version, "version");
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
            Objects.requireNonNull(os, "os");
            Objects.requireNonNull(arch, "arch");
            Objects.requireNonNull(packageType, "packageType");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(installFolderName, "installFolderName");
            javaHomeSubpath = javaHomeSubpath == null ? "" : javaHomeSubpath;
        }
    }
}
