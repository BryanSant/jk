// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.run.StepNames;
import build.jumpkick.plugin.build.Phase;

import build.jumpkick.cache.Cas;
import build.jumpkick.http.Http;
import build.jumpkick.model.RepositorySpec;
import build.jumpkick.model.ToolCoordSpec;
import build.jumpkick.repo.MavenRepo;
import build.jumpkick.repo.RepoGroup;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineKey;
import build.jumpkick.run.Step;
import build.jumpkick.run.StepKind;
import build.jumpkick.tool.ToolEnv;
import build.jumpkick.tool.ToolResolver;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * The shared tool-resolution pipeline behind {@code jk tool install}, {@code jk tool run}, and {@code
 * jk install <g:a:v>} — a {@link ToolResolver} run (transitive POM walk + jar fetches into the CAS)
 * hoisted out of the CLI so the resident engine can host it (Wave 4 of {@code
 * docs/architecture/slim-client.md}) while the commands' test-only in-process path builds the exact
 * same pipeline. The consumer of the resolved {@link ToolEnv} stays client-side: the launcher write
 * ({@code jk tool install} / {@code jk install}) or the inheritIO exec ({@code jk tool run}).
 */
public final class ToolPipelines {

    private ToolPipelines() {}

    /** The resolved tool env, populated by the {@code resolve-coord} step. */
    public static final PipelineKey<ToolEnv> TOOL_ENV = PipelineKey.of("tool-env", ToolEnv.class);

    /**
     * Build the single-step resolve pipeline for {@code spec}.
     *
     * @param spec the tool coordinate — pinned {@code g:a:v} or floating {@code g:a[@selector]}
     *     (the floating pick against maven-metadata happens inside the step)
     * @param withSpecs {@code --with} extras injected into the env's resolution (may be empty)
     * @param mainClassOverride the {@code --main} override, or {@code null} to read the primary
     *     jar's manifest
     * @param repoUrl overrides Maven Central ({@code null} = Central)
     * @param coordLabel the preformatted coordinate for the step label — the CLI's in-process path
     *     passes its themed {@code Coords.gav}, the engine passes the plain spec so no pre-themed
     *     text ever crosses the wire
     */
    public static Pipeline resolvePipeline(
            ToolCoordSpec spec,
            List<ToolCoordSpec> withSpecs,
            String bin,
            String mainClassOverride,
            URI repoUrl,
            Path cache,
            String coordLabel) {
        Step resolve = Step.builder(StepNames.RESOLVE_COORD).phase(Phase.RESOLVE)
                .kind(StepKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve " + coordLabel);
                    Cas cas = new Cas(cache);
                    URI url = repoUrl != null ? repoUrl : RepositorySpec.MAVEN_CENTRAL.url();
                    RepoGroup repos = RepoGroup.of(new MavenRepo("central", url, new Http(), cas));
                    try {
                        ctx.put(TOOL_ENV, new ToolResolver(repos).resolve(spec, bin, mainClassOverride, withSpecs));
                    } catch (RuntimeException | IOException e) {
                        ctx.error("resolve", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();
        return Pipeline.builder("tool-resolve").addStep(resolve).build();
    }
}
