// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Input to a {@link KotlinCompileStrategy}.
 *
 * <p>{@code kotlinHome} points at the Kotlin distribution root (the
 * directory whose {@code bin/kotlinc} we exec). It's nullable: when
 * absent the strategy is free to look in well-known locations
 * ({@code KOTLIN_HOME}, {@code ~/.jk/tools/kotlin/<default>/}).
 */
public record KotlincRequest(
        List<Path> sources,
        List<Path> classpath,
        Path outputDir,
        int jvmTarget,
        Path kotlinHome) {

    public KotlincRequest {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(outputDir, "outputDir");
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        if (jvmTarget < 8) {
            throw new IllegalArgumentException("jvmTarget must be >= 8, got: " + jvmTarget);
        }
    }

    /** Back-compat constructor without explicit kotlinHome. */
    public KotlincRequest(List<Path> sources, List<Path> classpath, Path outputDir, int jvmTarget) {
        this(sources, classpath, outputDir, jvmTarget, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<Path> sources = List.of();
        private List<Path> classpath = List.of();
        private Path outputDir;
        private int jvmTarget = 21;
        private Path kotlinHome;

        public Builder sources(List<Path> v) { this.sources = v; return this; }
        public Builder classpath(List<Path> v) { this.classpath = v; return this; }
        public Builder outputDir(Path v) { this.outputDir = v; return this; }
        public Builder jvmTarget(int v) { this.jvmTarget = v; return this; }
        public Builder kotlinHome(Path v) { this.kotlinHome = v; return this; }

        public KotlincRequest build() {
            return new KotlincRequest(sources, classpath, outputDir, jvmTarget, kotlinHome);
        }
    }
}
