// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat;

import java.net.URI;
import java.util.Objects;

/**
 * Metadata for a downloadable Maven or Gradle distribution. The
 * {@link MavenResolver} / {@link GradleResolver} produces these; the
 * {@link ToolInstaller} consumes them.
 *
 * <p>{@link #sha256} is optional: wrapper-properties-derived distributions
 * may not carry one (Maven Wrapper does, Gradle Wrapper sometimes does).
 * The installer skips verification when null/blank.
 */
public record ToolDistribution(BuildTool tool, String version, URI downloadUri, String archiveType, String sha256) {

    public ToolDistribution {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(downloadUri, "downloadUri");
        Objects.requireNonNull(archiveType, "archiveType");
        if (!archiveType.equals("zip") && !archiveType.equals("tar.gz")) {
            throw new IllegalArgumentException("unsupported archive type: " + archiveType);
        }
    }

    public ToolDistribution(BuildTool tool, String version, URI downloadUri, String archiveType) {
        this(tool, version, downloadUri, archiveType, null);
    }
}
