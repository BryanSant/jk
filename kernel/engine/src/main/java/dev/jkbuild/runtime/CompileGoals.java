// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.run.Goal;
import java.nio.file.Path;

/**
 * The shared {@code jk compile} goal — the {@link BuildPipeline} in compile-only mode (lock → sync
 * → compile, no resources/tests/packaging) — hoisted out of the CLI so the resident engine can host
 * the verb (Wave 3 of {@code docs/architecture/slim-client.md}) while the command's test-only
 * in-process path builds the exact same goal.
 */
public final class CompileGoals {

    private CompileGoals() {}

    /**
     * Build the compile-only goal for {@code dir}. The pipeline auto-locks on first run and
     * re-locks when {@code jk.toml} changed, exactly as {@code jk build}/{@code jk test} do —
     * session config ({@code offline}/{@code force}/{@code refresh}) is read off the ambient
     * {@link dev.jkbuild.config.SessionContext} at phase-run time.
     */
    public static Goal compileGoal(Path dir, Path cache, String profileName, boolean verbose) {
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                dir,
                cache,
                buildFile,
                lockFile,
                lockFile.getParent(),
                1,
                0,
                profileName,
                null,
                /* skipTests */ true,
                verbose, /* testOnly */
                false, /* compileOnly */
                true);
        return BuildPipeline.coreBuilder(inputs).build();
    }
}
