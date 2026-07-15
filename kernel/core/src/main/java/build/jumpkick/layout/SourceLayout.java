// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.layout;

import build.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Decides a project's concrete source layout — the shared rule behind {@code project.layout}
 * (rehomed from the engine's {@code CompileSupport} in the slim-client Stage 5 split: the client's
 * exporters/scaffolding need the same answer engine-less, and the two sides must never disagree).
 */
public final class SourceLayout {

    private SourceLayout() {}

    /**
     * True when {@code project} uses the simple layout ({@code src/} + {@code test/} at the root):
     *
     * <ul>
     *   <li>{@code "simple"} — always.
     *   <li>{@code "traditional"} — never (Maven layout).
     *   <li>{@code "auto"} or absent — infer from the directory tree: simple when both {@code
     *       src/main/kotlin} and {@code src/main/java} are absent or empty, traditional otherwise.
     * </ul>
     */
    public static boolean isSimpleLayout(JkBuild.Project project, Path projectDir) {
        return switch (project.layout()) {
            case SIMPLE -> true;
            case TRADITIONAL -> false;
            case AUTO ->
                !anySourceUnder(projectDir.resolve("src/main/kotlin"), ".kt", ".java")
                        && !anySourceUnder(projectDir.resolve("src/main/java"), ".kt", ".java");
        };
    }

    private static boolean anySourceUnder(Path root, String... extensions) {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.anyMatch(p -> {
                if (!Files.isRegularFile(p)) return false;
                String name = p.getFileName().toString();
                for (String ext : extensions) if (name.endsWith(ext)) return true;
                return false;
            });
        } catch (IOException e) {
            return false;
        }
    }
}
