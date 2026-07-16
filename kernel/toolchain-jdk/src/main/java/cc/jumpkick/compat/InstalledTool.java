// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import java.nio.file.Path;
import java.util.Objects;

/** A build tool that {@link ToolInstaller} has placed under {@code $JK_CACHE_DIR/tools/}. */
public record InstalledTool(BuildTool tool, String version, Path home) {

    public InstalledTool {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(home, "home");
    }

    /** Absolute path to the launcher under {@code <home>/bin/}. */
    public Path binary() {
        return home.resolve("bin").resolve(tool.binaryName());
    }
}
