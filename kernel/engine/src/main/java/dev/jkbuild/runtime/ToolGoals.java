// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.ToolCoordSpec;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.tool.ToolEnv;
import dev.jkbuild.tool.ToolResolver;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * The shared tool-resolution goal behind {@code jk tool install}, {@code jk tool run}, and {@code
 * jk install <g:a:v>} — a {@link ToolResolver} run (transitive POM walk + jar fetches into the CAS)
 * hoisted out of the CLI so the resident engine can host it (Wave 4 of {@code
 * docs/architecture/slim-client.md}) while the commands' test-only in-process path builds the exact
 * same goal. The consumer of the resolved {@link ToolEnv} stays client-side: the launcher write
 * ({@code jk tool install} / {@code jk install}) or the inheritIO exec ({@code jk tool run}).
 */
public final class ToolGoals {

    private ToolGoals() {}

    /** The resolved tool env, populated by the {@code resolve-coord} phase. */
    public static final GoalKey<ToolEnv> TOOL_ENV = GoalKey.of("tool-env", ToolEnv.class);

    /**
     * Build the single-phase resolve goal for {@code spec}.
     *
     * @param spec the tool coordinate — pinned {@code g:a:v} or floating {@code g:a[@selector]}
     *     (the floating pick against maven-metadata happens inside the phase)
     * @param withSpecs {@code --with} extras injected into the env's resolution (may be empty)
     * @param mainClassOverride the {@code --main} override, or {@code null} to read the primary
     *     jar's manifest
     * @param repoUrl overrides Maven Central ({@code null} = Central)
     * @param coordLabel the preformatted coordinate for the phase label — the CLI's in-process path
     *     passes its themed {@code Coords.gav}, the engine passes the plain spec so no pre-themed
     *     text ever crosses the wire
     */
    public static Goal resolveGoal(
            ToolCoordSpec spec,
            List<ToolCoordSpec> withSpecs,
            String bin,
            String mainClassOverride,
            URI repoUrl,
            Path cache,
            String coordLabel) {
        Phase resolve = Phase.builder("resolve-coord")
                .kind(PhaseKind.IO)
                .scope(1)
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
        return Goal.builder("tool-resolve").addPhase(resolve).build();
    }
}
