// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.StepNames;
import cc.jumpkick.plugin.build.Phase;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.ToolCoordSpec;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepKind;
import cc.jumpkick.tool.ToolEnv;
import cc.jumpkick.tool.ToolResolver;
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
