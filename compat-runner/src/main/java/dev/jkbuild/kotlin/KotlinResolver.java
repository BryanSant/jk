// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.kotlin;

import dev.jkbuild.compat.BuildTool;
import dev.jkbuild.compat.ToolDistribution;

import java.net.URI;

/**
 * Picks the Kotlin distribution to use for compiling {@code .kt} sources.
 * Mirrors {@link dev.jkbuild.mvn.MavenResolver} / {@link dev.jkbuild.gradle.GradleResolver}:
 * a default pinned version, downloaded once into
 * {@code $JK_CACHE_DIR/tools/kotlin/<version>/} and reused thereafter.
 *
 * <p>v0.6 first iteration: no project-level pin yet (a {@code project.kotlin}
 * field on {@code jk.toml} lands when more user code lives in Kotlin).
 * {@link #defaultDistribution()} is the single source of truth.
 */
public final class KotlinResolver {

    /**
     * jk's bundled default Kotlin version. Floor is 2.4.0: the Kotlin compile
     * path runs the Build Tools API via its {@code KotlinToolchains} entry
     * point, which only exists in 2.4.0+.
     */
    public static final String DEFAULT_VERSION = "2.4.0";

    private static final String DEFAULT_BASE =
            "https://github.com/JetBrains/kotlin/releases/download/";

    private KotlinResolver() {}

    public static ToolDistribution defaultDistribution() {
        URI uri = URI.create(DEFAULT_BASE + "v" + DEFAULT_VERSION
                + "/kotlin-compiler-" + DEFAULT_VERSION + ".zip");
        return new ToolDistribution(BuildTool.KOTLIN, DEFAULT_VERSION, uri, "zip");
    }
}
