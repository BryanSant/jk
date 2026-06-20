// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workspace-internal classpath resolution, focused on {@code [dependencies.export]}
 * acting like a main dependency that also rides transitively to consumers.
 */
class WorkspaceClasspathTest {

    @Test
    void export_sibling_is_on_the_declaring_members_own_classpath(@TempDir Path root) throws Exception {
        scaffold(root);
        JkBuild app = JkBuildParser.parse(root.resolve("app/jk.toml"));
        var result = WorkspaceClasspath.resolve(root.resolve("app"), app,
                Set.of(Scope.EXPORT, Scope.MAIN));
        // `app` declares `lib` in [dependencies.export]; it must be on app's own classpath.
        assertThat(jarNames(result)).anyMatch(n -> n.startsWith("lib-"));
    }

    @Test
    void export_deps_ride_transitively_to_a_consumer(@TempDir Path root) throws Exception {
        scaffold(root);
        JkBuild top = JkBuildParser.parse(root.resolve("top/jk.toml"));
        var result = WorkspaceClasspath.resolve(root.resolve("top"), top,
                Set.of(Scope.EXPORT, Scope.MAIN));
        // `top` depends on `app` (main); `app` exports `lib` — so `top` transitively
        // gets BOTH app and lib (the export rider).
        List<String> names = jarNames(result);
        assertThat(names).anyMatch(n -> n.startsWith("app-"));
        assertThat(names).anyMatch(n -> n.startsWith("lib-"));
    }

    /** lib  ←(export)— app  ←(main)— top */
    private static void scaffold(Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                [project]
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                jdk = "25"

                [workspace]
                members = ["lib", "app", "top"]
                """);
        member(root, "lib", "");
        member(root, "app", """
                [dependencies.export]
                lib = { workspace = true }
                """);
        member(root, "top", """
                [dependencies.main]
                app = { workspace = true }
                """);
    }

    private static void member(Path root, String name, String depsBlock) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "com.ex"
                name = "%s"
                version = "0.1.0"
                jdk = "25"
                layout = "simple"

                %s""".formatted(name, depsBlock));
    }

    private static List<String> jarNames(WorkspaceClasspath.Result result) {
        return result.siblingClosureJars().stream()
                .map(p -> p.getFileName().toString())
                .toList();
    }
}
