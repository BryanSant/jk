// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.repo.RepoGroup;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Builds a checked-out {@code jk.toml} project into a jar + POM headlessly — the engine of
 * git-source dependencies (docs/git-source-deps.md). A thin adapter over the general {@link
 * LocalProjectBuilder} (resolve → compile → jar → POM, no CLI machinery), kept as the git-specific
 * entry point so {@link GitSourceMaterializer} and its test stay unchanged.
 *
 * <p>Build scope (Java + Kotlin, layout-aware, the project's own Maven deps) lives in {@link
 * LocalProjectBuilder}; test execution, native image, and signing remain out — a git dependency
 * just needs its main artifact + POM.
 */
public final class GitProjectBuilder {

    private GitProjectBuilder() {}

    /** The built artifact: its coordinate/version plus the on-disk jar path and POM text. */
    record Built(String group, String artifact, String version, Path jar, String pomXml) {
        String coordinate() {
            return group + ":" + artifact + ":" + version;
        }
    }

    /**
     * Build {@code project} (rooted at {@code projectDir}) and produce the jar + POM for coordinate
     * {@code group:artifact:version}. Resolves the project's own dependencies through {@code repos}
     * (no network when it declares none); compiles with {@code javaHome}.
     */
    static Built build(
            Path projectDir,
            JkBuild project,
            String group,
            String artifact,
            String version,
            Path javaHome,
            Cas cas,
            RepoGroup repos,
            String jkVersion)
            throws IOException, InterruptedException {
        LocalProjectBuilder.Built b = LocalProjectBuilder.build(
                projectDir, project, group, artifact, version, javaHome, cas, repos, jkVersion);
        return new Built(b.group(), b.artifact(), b.version(), b.jar(), b.pomXml());
    }
}
