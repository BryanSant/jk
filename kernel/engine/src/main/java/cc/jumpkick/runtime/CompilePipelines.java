// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.Pipeline;
import java.nio.file.Path;

/**
 * The shared {@code jk compile} pipeline — the {@link BuildPipelines} in compile-only mode (lock → sync
 * → compile, no resources/tests/packaging) — hoisted out of the CLI so the resident engine can host
 * the command (Wave 3 of {@code docs/architecture/slim-client.md}) while the command's test-only
 * in-process path builds the exact same pipeline.
 */
public final class CompilePipelines {

    private CompilePipelines() {}

    /**
     * Build the compile-only pipeline for {@code dir}. The pipeline auto-locks on first run and
     * re-locks when {@code jk.toml} changed, exactly as {@code jk build}/{@code jk test} do —
     * session config ({@code offline}/{@code force}/{@code refresh}) is read off the ambient
     * {@link cc.jumpkick.config.SessionContext} at step-run time.
     */
    public static Pipeline compilePipeline(Path dir, Path cache, String profileName, boolean verbose) {
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        BuildPipelines.Inputs inputs = new BuildPipelines.Inputs(
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
                true,
                java.util.Set.of(),
                cc.jumpkick.config.SessionContext.current());
        return BuildPipelines.coreBuilder(inputs).build();
    }
}
