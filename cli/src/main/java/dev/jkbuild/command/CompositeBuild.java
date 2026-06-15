// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.runtime.BuildGraph;
import dev.jkbuild.runtime.BuildPipeline;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Builds a project's transitive composite ({@code path} / branch-git) dependency
 * units from source — compile-only, in dependency order — via the SAME real
 * pipeline as any project ({@code BuildPipeline.coreBuilder}). jk's
 * {@code includeBuild} analog, shared by {@code jk build}, {@code jk run}, and
 * {@code jk install} so each builds its composite deps before the classpath that
 * consumes them ({@code CompositeLocator} then locates the jars).
 */
final class CompositeBuild {

    private CompositeBuild() {}

    /**
     * Resolve the composite graph rooted at {@code entryDir}/{@code entry} and build
     * each dependency unit (compile-only). Returns 0 on success, else an exit code
     * (errors already printed). A no-op (1-unit graph) when no composite deps exist.
     */
    static int buildDependencies(Path entryDir, JkBuild entry, Path cache, Path jdksDir,
                                 String profileName, GlobalOptions global)
            throws IOException, InterruptedException {
        BuildGraph.Result graph = BuildGraph.resolve(entryDir, entry, cache.resolve("git"));
        if (graph.hasErrors()) {
            for (String err : graph.errors()) System.err.println("error[composite]: " + err);
            return 2;
        }
        for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
            if (!unit.isDependency()) continue;           // root/members built by the normal flow
            Path dir = unit.dir();
            BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                    dir, cache, dir.resolve("jk.toml"), dir.resolve("jk.lock"), dir,
                    1, 0, profileName, jdksDir, true /* skipTests */, global.verbose);
            Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
            BuildPipeline.appendDeclaredTails(builder, inputs);
            Goal goal = builder.build();
            if (!global.outputIsJson()) {
                System.out.println("── building dependency " + unit.coord() + " ──");
            }
            ConsoleSpec spec = new ConsoleSpec("Build",
                    r -> "Built " + unit.coord(), r -> "Build failed: " + unit.coord());
            GoalResult result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), cache, spec,
                    BuildCommand.buildTarget(dir.resolve("jk.toml"), dir));
            if (!result.success()) {
                System.err.println("jk: composite dependency " + unit.coord() + " failed to build");
                return 1;
            }
        }
        return 0;
    }
}
