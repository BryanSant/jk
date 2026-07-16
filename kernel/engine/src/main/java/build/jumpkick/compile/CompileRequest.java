// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Input to {@link JavacRunner#compile(CompileRequest)}: where to find sources, what to put on the
 * classpath, what release to target, and (optionally) where to write {@code .class} files.
 *
 * <p>When {@link #outputDir()} is {@code null}, the driver runs in "check" mode — diagnostics are
 * still produced but no output is written. Backs {@code jk check}.
 */
public record CompileRequest(
        List<Path> sources,
        List<Path> classpath,
        Path outputDir,
        int release,
        List<String> extraOptions,
        Path javaHome,
        List<Path> processorPath) {

    public CompileRequest {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(extraOptions, "extraOptions");
        Objects.requireNonNull(processorPath, "processorPath");
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        extraOptions = List.copyOf(extraOptions);
        processorPath = List.copyOf(processorPath);
        if (release < 8) {
            throw new IllegalArgumentException("release must be >= 8, got: " + release);
        }
        // javaHome nullable: the subprocess strategy falls back to
        // System.getProperty("java.home") when null.
    }

    /** Back-compat constructor (no annotation-processor path). */
    public CompileRequest(
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            int release,
            List<String> extraOptions,
            Path javaHome) {
        this(sources, classpath, outputDir, release, extraOptions, javaHome, List.of());
    }

    /** Back-compat constructor (no explicit javaHome — strategy picks one). */
    public CompileRequest(
            List<Path> sources, List<Path> classpath, Path outputDir, int release, List<String> extraOptions) {
        this(sources, classpath, outputDir, release, extraOptions, null, List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isCheckOnly() {
        return outputDir == null;
    }

    public static final class Builder {
        private List<Path> sources = List.of();
        private List<Path> classpath = List.of();
        private Path outputDir;
        private int release = 25;
        private List<String> extraOptions = List.of();
        private Path javaHome;
        private List<Path> processorPath = List.of();

        public Builder sources(List<Path> sources) {
            this.sources = sources;
            return this;
        }

        public Builder classpath(List<Path> classpath) {
            this.classpath = classpath;
            return this;
        }

        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        public Builder release(int release) {
            this.release = release;
            return this;
        }

        public Builder extraOptions(List<String> extraOptions) {
            this.extraOptions = extraOptions;
            return this;
        }

        public Builder javaHome(Path javaHome) {
            this.javaHome = javaHome;
            return this;
        }

        public Builder processorPath(List<Path> processorPath) {
            this.processorPath = processorPath;
            return this;
        }

        public CompileRequest build() {
            return new CompileRequest(sources, classpath, outputDir, release, extraOptions, javaHome, processorPath);
        }
    }
}
