// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.repo.EffectivePom;
import dev.jkbuild.repo.EffectivePomBuilder;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.Pom;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.pubgrub.Diagnostics;
import dev.jkbuild.resolver.pubgrub.PackageSource;
import dev.jkbuild.resolver.pubgrub.PubGrubSolver;
import dev.jkbuild.resolver.pubgrub.Term;
import dev.jkbuild.resolver.pubgrub.UnsatisfiableException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The {@link Resolver} implementation backed by the PubGrub solver. Glue between jk's {@link
 * Dependency}/{@link Resolution} types and the solver's {@link Term}/decision map.
 */
public final class PubGrubResolver implements Resolver {

    private static final String ROOT_PKG = "<root>";
    private static final String ROOT_VERSION = "0.0.0";

    private final PackageSource source;
    private final EffectivePomBuilder pomBuilder;

    public PubGrubResolver(MavenRepo repo) {
        this(RepoGroup.of(repo));
    }

    public PubGrubResolver(RepoGroup repos) {
        this(repos, Map.of());
    }

    /**
     * @param bomConstraints {@code group:artifact → pinned version} from the user's platform BOMs.
     *     Empty map = no BOM constraints.
     */
    public PubGrubResolver(RepoGroup repos, Map<String, String> bomConstraints) {
        this(repos, bomConstraints, Map.of());
    }

    /**
     * Conservative-lock variant: {@code lockedVersionPrefs} moves each locked version to the front of
     * PubGrub's candidate list so the solver selects it first. If a new dep's constraint rules it
     * out, PubGrub backtracks to the next available version naturally.
     */
    public PubGrubResolver(
            RepoGroup repos, Map<String, String> bomConstraints, Map<String, String> lockedVersionPrefs) {
        EffectivePomBuilder builder = new EffectivePomBuilder(repos);
        this.pomBuilder = builder;
        this.source = new MavenPackageSource(repos, builder, bomConstraints, lockedVersionPrefs);
    }

    /** Test seam: lets unit tests inject an in-memory {@link PackageSource}. */
    PubGrubResolver(PackageSource source, EffectivePomBuilder pomBuilder) {
        this.source = Objects.requireNonNull(source, "source");
        this.pomBuilder = pomBuilder;
    }

    @Override
    public Resolution resolve(List<Dependency> roots) throws IOException, InterruptedException {
        List<Term> rootTerms = new ArrayList<>(roots.size());
        Map<String, String> rootDepNames = new HashMap<>();
        for (Dependency dep : roots) {
            rootTerms.add(Term.positive(dep.module(), VersionSelectors.toVersionSet(dep.version())));
            // Skip workspace placeholders — they never hit the network so
            // the artifact-defaulting hint would be misleading there.
            if (!dep.module().startsWith("workspace:")) {
                rootDepNames.put(dep.module(), dep.library());
            }
        }

        Map<String, String> decisions;
        try {
            decisions = new PubGrubSolver(source).solve(ROOT_PKG, ROOT_VERSION, rootTerms);
        } catch (UnsatisfiableException e) {
            boolean ansi =
                    System.console() != null && !"dumb".equals(System.getenv("TERM")) && System.getenv("CI") == null;
            throw new UnsatisfiableException(Diagnostics.render(e.rootCause(), rootDepNames, ansi), e.rootCause());
        }

        // Drop the synthetic root from the result and build the per-module dep lists.
        decisions = new TreeMap<>(decisions);
        decisions.remove(ROOT_PKG);

        Map<String, Set<String>> dependsOn = new HashMap<>();
        for (Map.Entry<String, String> e : decisions.entrySet()) {
            Set<String> deps = new LinkedHashSet<>();
            if (pomBuilder != null) {
                EffectivePom pom = pomBuilder.build(toCoord(e.getKey(), e.getValue()));
                for (Pom.Dep d : pom.dependencies()) {
                    if (d.optional()) continue;
                    String scope = d.scope();
                    if (scope != null && !scope.isEmpty() && !scope.equals("compile") && !scope.equals("runtime"))
                        continue;
                    if (d.version() == null || d.version().isBlank()) continue;
                    if (!decisions.containsKey(d.module())) continue;
                    deps.add(d.module() + "@" + decisions.get(d.module()));
                }
            }
            dependsOn.put(e.getKey(), deps);
        }

        Map<String, Resolution.ResolvedModule> out = new TreeMap<>();
        for (Map.Entry<String, String> e : decisions.entrySet()) {
            out.put(
                    e.getKey(),
                    new Resolution.ResolvedModule(
                            e.getKey(), e.getValue(), new ArrayList<>(dependsOn.getOrDefault(e.getKey(), Set.of()))));
        }
        return new Resolution(out);
    }

    private static Coordinate toCoord(String module, String version) {
        int colon = module.indexOf(':');
        return Coordinate.of(module.substring(0, colon), module.substring(colon + 1), version);
    }
}
