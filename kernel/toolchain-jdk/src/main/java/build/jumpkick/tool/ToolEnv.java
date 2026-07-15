// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.tool;

import build.jumpkick.model.Coordinate;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A resolved tool: the primary Maven coord, its launcher name, the {@code Main-Class} to exec, and
 * the transitive classpath (paths into the CAS).
 *
 * <p>Produced by {@link ToolResolver}, consumed by {@link ToolLauncher} for both {@code jk install}
 * (persistent launcher) and {@code jk exec} (one-shot ephemeral exec).
 */
public record ToolEnv(String binName, Coordinate primary, String mainClass, List<Path> classpath) {

    /**
     * {@link #mainClass} sentinel for a platform-native binary (PRD §20.4): {@link #classpath}
     * holds exactly the binary to exec — there is no JVM in the launch at all.
     */
    public static final String NATIVE_BINARY = "native-binary";

    /** True when this env execs a native binary instead of {@code java -cp}. */
    public boolean isNativeBinary() {
        return NATIVE_BINARY.equals(mainClass);
    }

    public ToolEnv {
        Objects.requireNonNull(binName, "binName");
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(mainClass, "mainClass");
        Objects.requireNonNull(classpath, "classpath");
        classpath = List.copyOf(classpath);
    }
}
