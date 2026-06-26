// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Input to {@link KotlincDriver}, which forks the {@code jk-kotlin-compiler}
 * worker to drive the Kotlin Build Tools API.
 *
 * <ul>
 *   <li>{@code sources} / {@code classpath} / {@code outputDir} / {@code jvmTarget}
 *       — the compilation itself. {@code classpath} is the <em>compilation</em>
 *       classpath (project deps + a version-matched kotlin-stdlib); the caller
 *       pairs it with {@code -no-stdlib} in {@code extraArgs}.</li>
 *   <li>{@code workerClasspath} — the worker JVM's own classpath: the worker jar
 *       plus the resolved Build Tools API implementation closure.</li>
 *   <li>{@code javaHome} — the JDK that hosts the worker JVM.</li>
 *   <li>{@code workingDir} — incremental state dir; {@code null} ⇒ a full
 *       (non-incremental) compile.</li>
 *   <li>{@code extraArgs} — free compiler arguments appended verbatim.</li>
 * </ul>
 */
public record KotlincRequest(
        List<Path> sources,
        List<Path> classpath,
        Path outputDir,
        int jvmTarget,
        List<Path> workerClasspath,
        Path javaHome,
        Path workingDir,
        Path snapshotDir,
        List<String> extraArgs) {

    public KotlincRequest {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(workerClasspath, "workerClasspath");
        Objects.requireNonNull(javaHome, "javaHome");
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        workerClasspath = List.copyOf(workerClasspath);
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        if (jvmTarget < 8) {
            throw new IllegalArgumentException("jvmTarget must be >= 8, got: " + jvmTarget);
        }
        if (workerClasspath.isEmpty()) {
            throw new IllegalArgumentException("workerClasspath must include the worker jar + BTA closure");
        }
    }

    public boolean incremental() {
        return workingDir != null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<Path> sources = List.of();
        private List<Path> classpath = List.of();
        private Path outputDir;
        private int jvmTarget = 21;
        private List<Path> workerClasspath = List.of();
        private Path javaHome;
        private Path workingDir;
        private Path snapshotDir;
        private List<String> extraArgs = List.of();

        public Builder sources(List<Path> v) {
            this.sources = v;
            return this;
        }

        public Builder classpath(List<Path> v) {
            this.classpath = v;
            return this;
        }

        public Builder outputDir(Path v) {
            this.outputDir = v;
            return this;
        }

        public Builder jvmTarget(int v) {
            this.jvmTarget = v;
            return this;
        }

        public Builder workerClasspath(List<Path> v) {
            this.workerClasspath = v;
            return this;
        }

        public Builder javaHome(Path v) {
            this.javaHome = v;
            return this;
        }

        public Builder workingDir(Path v) {
            this.workingDir = v;
            return this;
        }

        public Builder snapshotDir(Path v) {
            this.snapshotDir = v;
            return this;
        }

        public Builder extraArgs(List<String> v) {
            this.extraArgs = v;
            return this;
        }

        public KotlincRequest build() {
            return new KotlincRequest(
                    sources,
                    classpath,
                    outputDir,
                    jvmTarget,
                    workerClasspath,
                    javaHome,
                    workingDir,
                    snapshotDir,
                    extraArgs);
        }
    }
}
