// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Input to {@link KotlincDriver}, which forks the {@code jk-kotlin-compiler} worker to drive the
 * Kotlin Build Tools API.
 *
 * <ul>
 *   <li>{@code sources} / {@code classpath} / {@code outputDir} / {@code jvmTarget} — the
 *       compilation itself. {@code classpath} is the <em>compilation</em> classpath (project deps +
 *       a version-matched kotlin-stdlib); the caller pairs it with {@code -no-stdlib} in {@code
 *       extraArgs}.
 *   <li>{@code workerClasspath} — the worker JVM's own classpath: the worker jar plus the resolved
 *       Build Tools API implementation closure.
 *   <li>{@code javaHome} — the JDK that hosts the worker JVM.
 *   <li>{@code workingDir} — incremental state dir; {@code null} ⇒ a full (non-incremental)
 *       compile.
 *   <li>{@code extraArgs} — free compiler arguments appended verbatim.
 *   <li>{@code plugins} — compiler plugins (all-open, no-arg, ...) passed through the Build Tools
 *       API's typed {@code COMPILER_PLUGINS} argument: raw {@code -Xplugin}/{@code -P} strings in
 *       {@code extraArgs} are silently ignored by the BTA execution path.
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
        List<String> extraArgs,
        List<Plugin> plugins) {

    /** One compiler plugin: its id, jar, and {@code key=value} options. */
    public record Plugin(String id, Path jar, List<String> options) {

        public Plugin {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(jar, "jar");
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

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
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        if (jvmTarget < 8) {
            throw new IllegalArgumentException("jvmTarget must be >= 8, got: " + jvmTarget);
        }
        if (workerClasspath.isEmpty()) {
            throw new IllegalArgumentException("workerClasspath must include the worker jar + BTA closure");
        }
    }

    /** Back-compat constructor: no compiler plugins. */
    public KotlincRequest(
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            int jvmTarget,
            List<Path> workerClasspath,
            Path javaHome,
            Path workingDir,
            Path snapshotDir,
            List<String> extraArgs) {
        this(sources, classpath, outputDir, jvmTarget, workerClasspath, javaHome, workingDir, snapshotDir, extraArgs,
                List.of());
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
        private List<Plugin> plugins = List.of();

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

        public Builder plugins(List<Plugin> v) {
            this.plugins = v;
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
                    extraArgs,
                    plugins);
        }
    }
}
