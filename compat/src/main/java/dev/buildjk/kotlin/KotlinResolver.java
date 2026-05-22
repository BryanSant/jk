// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.kotlin;

import dev.buildjk.compat.BuildTool;
import dev.buildjk.compat.ToolDistribution;

import java.net.URI;

/**
 * Picks the Kotlin distribution to use for compiling {@code .kt} sources.
 * Mirrors {@link dev.buildjk.mvn.MavenResolver} / {@link dev.buildjk.gradle.GradleResolver}:
 * a default pinned version, downloaded once into
 * {@code ~/.jk/tools/kotlin/<version>/} and reused thereafter.
 *
 * <p>v0.6 first iteration: no project-level pin yet (a {@code project.kotlin}
 * field on {@code build.jk} lands when more user code lives in Kotlin).
 * {@link #defaultDistribution()} is the single source of truth.
 */
public final class KotlinResolver {

    /** jk's bundled default. Matches gradle/libs.versions.toml's {@code kotlin} pin. */
    public static final String DEFAULT_VERSION = "2.3.21";

    private static final String DEFAULT_BASE =
            "https://github.com/JetBrains/kotlin/releases/download/";

    private KotlinResolver() {}

    public static ToolDistribution defaultDistribution() {
        URI uri = URI.create(DEFAULT_BASE + "v" + DEFAULT_VERSION
                + "/kotlin-compiler-" + DEFAULT_VERSION + ".zip");
        return new ToolDistribution(BuildTool.KOTLIN, DEFAULT_VERSION, uri, "zip");
    }
}
