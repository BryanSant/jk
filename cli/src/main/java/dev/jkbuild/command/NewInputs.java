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
 *   <li>{@code name} — project name; doubles as the target directory's leaf.</li>
 *   <li>{@code artifact} — Maven artifactId. Often equal to {@code name} but
 *       can diverge (e.g., name = {@code my-app}, artifact = {@code my-app-core}).</li>
 *   <li>{@code jdkMajor} — JDK feature-release the scaffolder targets. Drives
 *       Java 25's instance-{@code main} syntax decision.</li>
 *   <li>{@code kotlinCompact} — when {@code true} (Kotlin only), {@code Main.kt}
 *       lands at {@code ./src/Main.kt} with no package declaration.</li>
 *   <li>{@code kotlinModuleName} — when present, written as
 *       {@code module = "..."} under {@code [project]}.</li>
 * </ul>
 */
public record NewInputs(
        String group,
        String name,
        String artifact,
        String jdk,
        int jdkMajor,
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
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(jdk, "jdk");
        Objects.requireNonNull(jdkIdentifier, "jdkIdentifier");
        Objects.requireNonNull(main, "main");
        Objects.requireNonNull(lang, "lang");
        Objects.requireNonNull(kotlinModuleName, "kotlinModuleName");
        Objects.requireNonNull(directory, "directory");
        deps = List.copyOf(deps);
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
