// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import dev.buildjk.model.Coordinate;
import dev.buildjk.model.Dependency;
import dev.buildjk.repo.EffectivePom;
import dev.buildjk.repo.EffectivePomBuilder;
import dev.buildjk.repo.MavenRepo;
import dev.buildjk.repo.Pom;
import dev.buildjk.repo.RepoGroup;
import dev.buildjk.resolver.pubgrub.Diagnostics;
import dev.buildjk.resolver.pubgrub.PackageSource;
import dev.buildjk.resolver.pubgrub.PubGrubSolver;
import dev.buildjk.resolver.pubgrub.Term;
import dev.buildjk.resolver.pubgrub.UnsatisfiableException;

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
 * The {@link Resolver} implementation backed by the PubGrub solver. Glue
 * between jk's {@link Dependency}/{@link Resolution} types and the
 * solver's {@link Term}/decision map.
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
        EffectivePomBuilder builder = new EffectivePomBuilder(repos);
        this.pomBuilder = builder;
        this.source = new MavenPackageSource(repos, builder);
    }

    /** Test seam: lets unit tests inject an in-memory {@link PackageSource}. */
    PubGrubResolver(PackageSource source, EffectivePomBuilder pomBuilder) {
        this.source = Objects.requireNonNull(source, "source");
        this.pomBuilder = pomBuilder;
    }

    @Override
    public Resolution resolve(List<Dependency> roots) throws IOException, InterruptedException {
        List<Term> rootTerms = new ArrayList<>(roots.size());
        for (Dependency dep : roots) {
            rootTerms.add(Term.positive(dep.module(), VersionSelectors.toVersionSet(dep.version())));
        }

        Map<String, String> decisions;
        try {
            decisions = new PubGrubSolver(source).solve(ROOT_PKG, ROOT_VERSION, rootTerms);
        } catch (UnsatisfiableException e) {
            throw new UnsatisfiableException(Diagnostics.render(e.rootCause()), e.rootCause());
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
                    if (scope != null && !scope.isEmpty()
                            && !scope.equals("compile") && !scope.equals("runtime")) continue;
                    if (d.version() == null || d.version().isBlank()) continue;
                    if (!decisions.containsKey(d.module())) continue;
                    deps.add(d.module() + "@" + decisions.get(d.module()));
                }
            }
            dependsOn.put(e.getKey(), deps);
        }

        Map<String, Resolution.ResolvedModule> out = new TreeMap<>();
        for (Map.Entry<String, String> e : decisions.entrySet()) {
            out.put(e.getKey(), new Resolution.ResolvedModule(
                    e.getKey(), e.getValue(),
                    new ArrayList<>(dependsOn.getOrDefault(e.getKey(), Set.of()))));
        }
        return new Resolution(out);
    }

    private static Coordinate toCoord(String module, String version) {
        int colon = module.indexOf(':');
        return Coordinate.of(
                module.substring(0, colon), module.substring(colon + 1), version);
    }
}
