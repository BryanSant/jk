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
 *   <li>{@code layout} — "simple" for flat ./src + ./test layout, "traditional" for Maven layout,
 *       or null/"auto" for auto-detection. Emitted as project.layout in jk.toml.</li>
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
        String layout,
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
     * Back-compat constructor: {@code javaRelease} defaults to {@code jdkMajor}.
     */
    public NewInputs(
            String group, String name, String jdk, int jdkMajor,
            Optional<String> jdkIdentifier, Optional<String> main, boolean shadow,
            boolean nativeImage, Language lang, String layout,
            Optional<String> kotlinModuleName, List<String> deps, boolean sample, Path directory) {
        this(group, name, jdk, jdkMajor, jdkMajor, jdkIdentifier, main, shadow,
                nativeImage, lang, layout, kotlinModuleName, deps, sample, directory);
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

    /** True when the chosen layout is "simple" (flat ./src + ./test). */
    public boolean isSimpleLayout() {
        return "simple".equalsIgnoreCase(layout);
    }

    public boolean isRunnable() {
        return main.isPresent();
    }
}
