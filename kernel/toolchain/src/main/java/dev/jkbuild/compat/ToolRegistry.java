// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Catalog of Maven and Gradle distributions installed under
 * {@code $JK_CACHE_DIR/tools/}. Layout:
 * {@code $JK_CACHE_DIR/tools/<slug>/<version>/}, where {@code <slug>} is
 * {@code maven} or {@code gradle} and {@code <version>} is the upstream
 * distribution version (e.g. {@code 3.9.9}, {@code 9.5.1}).
 */
public final class ToolRegistry {

    private final Path toolsRoot;

    public ToolRegistry(Path toolsRoot) {
        this.toolsRoot = Objects.requireNonNull(toolsRoot, "toolsRoot");
    }

    public Path toolsRoot() {
        return toolsRoot;
    }

    /** Installation directory for a given tool+version, whether or not it exists. */
    public Path installDir(BuildTool tool, String version) {
        return toolsRoot.resolve(tool.slug()).resolve(version);
    }

    public Optional<InstalledTool> find(BuildTool tool, String version) {
        Path dir = installDir(tool, version);
        return Files.isDirectory(dir) ? Optional.of(new InstalledTool(tool, version, dir)) : Optional.empty();
    }

    public List<InstalledTool> list(BuildTool tool) throws IOException {
        Path slugDir = toolsRoot.resolve(tool.slug());
        if (!Files.exists(slugDir)) return List.of();
        List<InstalledTool> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(slugDir)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(p ->
                            result.add(new InstalledTool(tool, p.getFileName().toString(), p)));
        }
        return result;
    }
}
