// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bridge record consumed by {@link NewScaffolder}. Both the picocli flag path
 * and the {@code tui.Wizard} interactive path converge on this type so the
 * scaffolder doesn't care how the user supplied the answers.
 *
 * <p>Field reminder:
 * <ul>
 *   <li>{@code name} — project name; the target directory's leaf and the
 *       value written as {@code [project].name} in {@code jk.toml}.</li>
 *   <li>{@code jdkMajor} — the JDK toolchain feature-release (which JDK runs
 *       the build). Defaults to the parent's for a member; user-pickable.</li>
 *   <li>{@code javaRelease} — the {@code java = N} compile target. Usually
 *       equal to {@code jdkMajor}, but a workspace member inherits the parent's
 *       release even when it diverges (e.g. {@code jdk = 25}, {@code java = 17}).
 *       Drives the instance-{@code main} syntax decision.</li>
 *   <li>{@code kotlinCompact} — when {@code true} (Kotlin only), {@code Main.kt}
 *       lands at {@code ./src/Main.kt} with no package declaration.</li>
 *   <li>{@code kotlinModuleName} — when present, written as
 *       {@code module = "..."} under {@code [project]}.</li>
 * </ul>
 */
public record NewInputs(
        String group,
        String name,
        String jdk,
        int jdkMajor,
        int javaRelease,
        Optional<String> jdkIdentifier,
        Optional<String> main,
        boolean shadow,
        boolean nativeImage,
        Language lang,
        boolean kotlinCompact,
        Optional<String> kotlinModuleName,
        List<String> deps,
        boolean sample,
        Path directory) {

    public NewInputs {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(jdk, "jdk");
        Objects.requireNonNull(jdkIdentifier, "jdkIdentifier");
        Objects.requireNonNull(main, "main");
        Objects.requireNonNull(lang, "lang");
        Objects.requireNonNull(kotlinModuleName, "kotlinModuleName");
        Objects.requireNonNull(directory, "directory");
        deps = List.copyOf(deps);
    }

    /**
     * Back-compat constructor: {@code javaRelease} defaults to {@code jdkMajor}
     * (the common case where the compile target equals the toolchain). Callers
     * that inherit a divergent member release use the canonical constructor.
     */
    public NewInputs(
            String group, String name, String jdk, int jdkMajor,
            Optional<String> jdkIdentifier, Optional<String> main, boolean shadow,
            boolean nativeImage, Language lang, boolean kotlinCompact,
            Optional<String> kotlinModuleName, List<String> deps, boolean sample, Path directory) {
        this(group, name, jdk, jdkMajor, jdkMajor, jdkIdentifier, main, shadow,
                nativeImage, lang, kotlinCompact, kotlinModuleName, deps, sample, directory);
    }

    public enum Language {
        JAVA, KOTLIN;

        public String hoconValue() {
            return switch (this) {
                case JAVA -> "java";
                case KOTLIN -> "kotlin";
            };
        }

        public String sourceDir() {
            return switch (this) {
                case JAVA -> "java";
                case KOTLIN -> "kotlin";
            };
        }
    }

    public boolean isRunnable() {
        return main.isPresent();
    }
}
