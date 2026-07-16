// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.Diagnostics;
import cc.jumpkick.resolver.pubgrub.PackageSource;
import cc.jumpkick.resolver.pubgrub.PubGrubSolver;
import cc.jumpkick.resolver.pubgrub.Term;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
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
    private KmpRedirects kmp = KmpRedirects.NONE;
    /** Optional palette injected by the CLI so diagnostic colors match the live theme. */
    cc.jumpkick.resolver.pubgrub.Diagnostics.Palette palette; // package-private for LockOrchestrator

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
        this(repos, bomConstraints, lockedVersionPrefs, KmpRedirects.NONE);
    }

    /** As above with KMP root-module redirect resolution (see {@link KmpRedirects}). */
    public PubGrubResolver(
            RepoGroup repos,
            Map<String, String> bomConstraints,
            Map<String, String> lockedVersionPrefs,
            KmpRedirects kmp) {
        EffectivePomBuilder builder = new EffectivePomBuilder(repos);
        this.pomBuilder = builder;
        this.kmp = kmp;
        this.source = new MavenPackageSource(repos, builder, bomConstraints, lockedVersionPrefs, kmp);
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
            if (!dep.isWorkspace()) {
                rootDepNames.put(dep.module(), dep.library());
            }
        }

        Map<String, String> decisions;
        try {
            decisions = new PubGrubSolver(source).solve(ROOT_PKG, ROOT_VERSION, rootTerms);
        } catch (UnsatisfiableException e) {
            boolean ansi =
                    System.console() != null && !"dumb".equals(System.getenv("TERM")) && System.getenv("CI") == null;
            // Use the injected palette (from the CLI theme) when available; fall back to the
            // built-in DEFAULT which hard-codes the same values as JkDarkTheme.
            cc.jumpkick.resolver.pubgrub.Diagnostics.Palette palette =
                    this.palette != null ? this.palette : (ansi ? cc.jumpkick.resolver.pubgrub.Diagnostics.Palette.DEFAULT : cc.jumpkick.resolver.pubgrub.Diagnostics.Palette.PLAIN);
            throw new UnsatisfiableException(Diagnostics.render(e.rootCause(), palette), e.rootCause());
        }

        // Drop the synthetic root from the result and build the per-module dep lists.
        decisions = new TreeMap<>(decisions);
        decisions.remove(ROOT_PKG);

        // KMP global variant exclusion (A5f finding 20): a platform artifact's own POM can name
        // a non-selected SIBLING concretely (datastore-core-okio-jvm → datastore-core-jvm) —
        // variant-aware in GMM space, a double-define at dex in POM space. When the selected
        // sibling made it into the resolution, the non-selected one leaves it; its dep edges
        // (built below) drop with it, and the selected artifact supplies the classes.
        for (var drop : kmp.droppedSiblings().entrySet()) {
            if (decisions.containsKey(drop.getValue())) {
                decisions.remove(drop.getKey());
            }
        }

        Map<String, Set<String>> dependsOn = new HashMap<>();
        for (Map.Entry<String, String> e : decisions.entrySet()) {
            Set<String> deps = new LinkedHashSet<>();
            if (pomBuilder != null) {
                // Mirror MavenPackageSource's KMP rewrite: the dep edges must show the
                // GMM-selected platform artifact, not the POM's platform fallback.
                var kmpSelection = kmp.selectionFor(e.getKey(), e.getValue());
                Set<String> kmpDropped = Set.of();
                if (kmpSelection.isPresent()) {
                    var target = kmpSelection.get().target();
                    String targetModule = target.group() + ":" + target.module();
                    if (decisions.containsKey(targetModule)) {
                        deps.add(targetModule + "@" + decisions.get(targetModule));
                    }
                    kmpDropped = kmpSelection.get().allTargets();
                }
                EffectivePom pom = pomBuilder.build(toCoord(e.getKey(), e.getValue()));
                for (Pom.Dep d : pom.dependencies()) {
                    if (d.optional()) continue;
                    if (kmpDropped.contains(d.module())) continue;
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
        return Coordinate.ofModule(module, version);
    }
}
