// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.tool;

import dev.jkbuild.model.Coordinate;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A resolved tool: the primary Maven coord, its launcher name, the
 * {@code Main-Class} to exec, and the transitive classpath (paths into
 * the CAS).
 *
 * <p>Produced by {@link ToolResolver}, consumed by {@link ToolLauncher}
 * for both {@code jk install} (persistent launcher) and {@code jk exec}
 * (one-shot ephemeral exec).
 */
public record ToolEnv(String binName, Coordinate primary, String mainClass, List<Path> classpath) {

    public ToolEnv {
        Objects.requireNonNull(binName, "binName");
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(mainClass, "mainClass");
        Objects.requireNonNull(classpath, "classpath");
        classpath = List.copyOf(classpath);
    }
}
