// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bridge record consumed by {@link InitScaffolder}. Both the picocli flag path
 * and the {@code tui.Wizard} interactive path converge on this type so the
 * scaffolder doesn't care how the user supplied the answers.
 */
public record InitInputs(
        String group,
        String name,
        String jdk,
        Optional<String> main,
        boolean shadow,
        boolean nativeImage,
        Language lang,
        List<String> deps,
        boolean sample,
        Path directory) {

    public InitInputs {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(jdk, "jdk");
        Objects.requireNonNull(main, "main");
        Objects.requireNonNull(lang, "lang");
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
