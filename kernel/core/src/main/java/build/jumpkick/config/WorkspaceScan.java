// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The client-side twin of {@link WorkspaceLocator}: the same ancestor walks, answered by a
 * {@link TomlScan} of {@code [workspace] modules} instead of a full parse — these run on client
 * paths (path display, clean, add) where the thin client ships no TOML parser. A root whose
 * manifest hides its module list behind exotic TOML reads as "not a workspace" (fail-soft,
 * never a wrong membership); the engine's own locator keeps full parser fidelity.
 */
public final class WorkspaceScan {

    private static final int MAX_DEPTH = 8192;

    private WorkspaceScan() {}

    /**
     * The nearest strict ancestor whose {@code jk.toml} declares workspace modules, or empty.
     * Mirrors {@link WorkspaceLocator#findEnclosingWorkspace} (no membership requirement — used
     * by commands about to create/register a module).
     */
    public static Optional<Path> findEnclosingWorkspace(Path dir) {
        Path candidate = dir.toAbsolutePath().normalize();
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break;
            if (isWorkspaceRoot(parent)) return Optional.of(parent);
            candidate = parent;
        }
        return Optional.empty();
    }

    /**
     * The workspace root that owns {@code moduleDir} (the ancestor whose {@code workspace.modules}
     * lists it), or empty. Mirrors {@link WorkspaceLocator#findRoot}.
     */
    public static Optional<Path> findRoot(Path moduleDir) {
        Path normalized = moduleDir.toAbsolutePath().normalize();
        Path candidate = normalized;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break;
            Path rootJkToml = parent.resolve("jk.toml");
            if (Files.exists(rootJkToml)) {
                String relative = parent.relativize(normalized).toString().replace('\\', '/');
                if (TomlScan.scan(rootJkToml, "workspace.modules")
                        .stringArray("workspace.modules")
                        .contains(relative)) {
                    return Optional.of(parent);
                }
            }
            candidate = parent;
        }
        return Optional.empty();
    }

    /** True when {@code dir/jk.toml} declares a non-empty {@code [workspace] modules} list. */
    public static boolean isWorkspaceRoot(Path dir) {
        Path toml = dir.resolve("jk.toml");
        if (!Files.exists(toml)) return false;
        return !TomlScan.scan(toml, "workspace.modules")
                .stringArray("workspace.modules")
                .isEmpty();
    }
}
