// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.runtime.RepoGroupBuilder;

import dev.jkbuild.runtime.GitSourceResolution;

import dev.jkbuild.runtime.CompileToolchain;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.model.WorkspaceMerge;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.LockOrchestrator;
import dev.jkbuild.resolver.VersionSelectors;
import dev.jkbuild.resolver.Versions;
import dev.jkbuild.resolver.pubgrub.VersionSet;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code jk lock} — resolve declared dependencies and write {@code jk.lock}.
 *
 * <p>Reads repositories + features from the project's jk.toml. Feature
 * selection: {@code --features=A,B} adds those features on top of the
 * declared {@code features.default}; {@code --no-default-features}
 * disables the default list entirely. Cargo semantics.
 *
 * <p>For workspace roots, locking cascades: after the root's own
 * {@code jk.lock} is written, each declared member is locked in
 * declaration order using its own {@code jk.lock} alongside its
 * {@code jk.toml}. {@code workspace:} placeholder deps are resolved to
 * real Maven coords before each member's solve; sibling-internal deps
 * are filtered out (they're injected at build time via
 * {@link dev.jkbuild.config.WorkspaceClasspath}).
 */
@Command(name = "lock", description = "Resolve versions for dependencies and write jk.lock")
public final class LockCommand implements Callable<Integer> {

    @Option(names = "--features", paramLabel = "<a,b,...>", split = ",",
            description = "Activate the listed features in addition to defaults.")
    List<String> features = List.of();

    @Option(names = "--no-default-features",
            description = "Don't activate the project's default features.")
    boolean noDefaultFeatures;

    @Option(names = "--repo-url",
            description = "Override declared repos with a single URL.",
            hidden = true)
    URI repoUrl;

    @Option(names = "--cache-dir",
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
            hidden = true)
    Path cacheDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    private static final GoalKey<JkBuild> EFFECTIVE = GoalKey.of("effective-build", JkBuild.class);
    private static final GoalKey<Lockfile> LOCKFILE = GoalKey.of("lockfile", Lockfile.class);

    @Override
    public Integer call() throws Exception {
        Path dir = global.workingDir();
        if (!Files.exists(dir.resolve("jk.toml"))) {
            System.err.println("jk lock: no jk.toml in " + dir);
            return 2;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        JkBuild root;
        try {
            root = JkBuildParser.parse(dir.resolve("jk.toml"));
        } catch (RuntimeException e) {
            System.err.println("jk lock: " + e.getMessage());
            return 2;
        }

        // When locking a workspace member directly, apply workspace context:
        // resolve workspace: placeholders and filter sibling-internal deps so
        // the solver only sees external Maven coords.
        JkBuild effectiveRoot = applyWorkspaceContextIfMember(dir, root);

        // Lock the current directory (root or standalone project).
        int result = lockSingleProject(dir, effectiveRoot, cache, "Dependency Lock");
        if (result != 0) return result;

        // Cascade: lock each declared workspace member in declaration order.
        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> members;
            try {
                members = WorkspaceLoader.loadMembers(dir, effectiveRoot);
            } catch (RuntimeException e) {
                System.err.println("jk lock: " + e.getMessage());
                return 2;
            }
            for (Map.Entry<Path, JkBuild> entry : members.entrySet()) {
                Path memberDir = entry.getKey();
                JkBuild rawMember = entry.getValue();
                // Resolve workspace:* placeholders and filter sibling-internal deps
                // so the member's lock only contains resolvable external coords.
                JkBuild effectiveMember = WorkspaceMerge.applyToMember(
                        effectiveRoot, rawMember, members.values());
                String memberLabel = dir.getFileName() + "/"
                        + dir.relativize(memberDir);
                int memberResult = lockSingleProject(memberDir, effectiveMember, cache, memberLabel);
                if (memberResult != 0) return memberResult;
            }
        }
        return 0;
    }

    /**
     * When invoked from a workspace member (not the root), discover the
     * enclosing workspace and apply member context: resolve {@code workspace:}
     * placeholders and filter out sibling-internal dep coords so the solver
     * only sees external Maven coordinates. Returns {@code project} unchanged
     * if it is a workspace root or no enclosing workspace is found.
     */
    private static JkBuild applyWorkspaceContextIfMember(Path dir, JkBuild project) {
        if (project.isWorkspaceRoot()) return project;
        try {
            var rootOpt = WorkspaceLocator.findRoot(dir);
            if (rootOpt.isEmpty()) return project;
            Path wsRoot = rootOpt.get();
            JkBuild wsRootBuild = JkBuildParser.parse(wsRoot.resolve("jk.toml"));
            if (!wsRootBuild.isWorkspaceRoot()) return project;
            var siblings = WorkspaceLoader.loadMembers(wsRoot, wsRootBuild);
            return WorkspaceMerge.applyToMember(wsRootBuild, project, siblings.values());
        } catch (Exception ignored) {
            // Workspace discovery is best-effort; fall through to a direct lock.
            return project;
        }
    }

    /**
     * Run the three-phase lock pipeline (parse → resolve → write) for one
     * project directory. {@code effective} is the pre-parsed {@link JkBuild}
     * with any {@code workspace:} placeholders already resolved.
     */
    private int lockSingleProject(Path dir, JkBuild effective, Path cache, String label)
            throws Exception {
        Path lockFile = dir.resolve("jk.lock");

        AtomicInteger resolveEstimate = new AtomicInteger(0);

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    ctx.put(EFFECTIVE, effective);
                    ctx.progress(1);
                })
                .build();

        Phase resolve = Phase.builder("resolve")
                .label("Resolving")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .scope(() -> {
                    // Best case: existing lockfile is accurate (re-runs).
                    try {
                        int n = LockfileReader.read(lockFile).packages().size();
                        if (n > 0) { resolveEstimate.set(n); return n; }
                    } catch (Exception ignored) {}
                    // Fallback: declared deps × rough transitive expansion.
                    try {
                        int declared = effective.dependencies().byScope().values().stream()
                                .mapToInt(List::size).sum();
                        int estimate = Math.max(5, declared * 8);
                        resolveEstimate.set(estimate);
                        return estimate;
                    } catch (Exception ignored) {}
                    resolveEstimate.set(20);
                    return 20;
                })
                .execute(ctx -> {
                    ctx.label("Resolving");
                    JkBuild eff = ctx.require(EFFECTIVE);
                    Cas cas = new Cas(cache);
                    if (global.offline && Files.exists(lockFile)) {
                        try {
                            Lockfile existing = LockfileReader.read(lockFile);
                            requireOfflineSatisfiable(eff, existing, cas);
                            ctx.progress(existing.packages().size());
                            ctx.put(LOCKFILE, existing);
                            return;
                        } catch (Exception e) {
                            ctx.error("resolve", e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }
                    RepoGroup baseRepos = RepoGroupBuilder.buildFor(eff, repoUrl, cas);
                    Map<String, String> lockedShas = Map.of();
                    if (Files.exists(lockFile)) {
                        try {
                            lockedShas = GitSourceResolution.lockedImmutableShas(
                                    LockfileReader.read(lockFile));
                        } catch (Exception ignored) {
                            // Unreadable prior lock → nothing to verify against.
                        }
                    }
                    GitSourceResolution.Prepared prep;
                    try {
                        prep = GitSourceResolution.prepare(
                                eff, baseRepos, cas,
                                CompileToolchain.resolveJavaHome(dir), Jk.VERSION, lockedShas);
                    } catch (Exception e) {
                        ctx.error("resolve", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    RepoGroup repos = prep.repos();
                    LockOrchestrator orchestrator = new LockOrchestrator(repos);
                    dev.jkbuild.resolver.ResolveObserver observer =
                            new dev.jkbuild.resolver.ResolveObserver() {
                        @Override
                        public void onTotal(int total) {
                            int delta = total - resolveEstimate.get();
                            if (delta > 0) ctx.updateScope(delta);
                        }
                        @Override
                        public void onPackage(String module, String version) {
                            ctx.label("Resolved " + Coords.module(module, version));
                            ctx.progress(1);
                        }
                    };
                    try {
                        Lockfile lock = orchestrator.lock(
                                prep.project(), Jk.VERSION, features, !noDefaultFeatures, observer);
                        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
                        String kotlinVersion = resolveKotlinVersion(eff, repos);
                        if (kotlinVersion != null) {
                            ctx.label("resolved kotlin " + kotlinVersion);
                            lock = lock.withKotlin(kotlinVersion);
                        }
                        ctx.put(LOCKFILE, lock);
                    } catch (Exception e) {
                        ctx.error("resolve", e.getMessage());
                        throw new RuntimeException(e);
                    }
                })
                .build();

        Phase write = Phase.builder("write-lockfile")
                .requires("resolve")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("write " + lockFile.getFileName());
                    LockfileWriter.write(ctx.require(LOCKFILE), lockFile);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("lock")
                .addPhase(parseBuild)
                .addPhase(resolve)
                .addPhase(write)
                .build();

        ConsoleSpec spec = new ConsoleSpec(label,
                r -> {
                    int pkgs = goal.get(LOCKFILE).orElseThrow().packages().size();
                    String inTime = Theme.colorize(
                            "in " + BuildCommand.fmtDuration(r.duration()),
                            Theme.active().darkGray());
                    return "Resolved " + pkgs + " dependenc"
                            + (pkgs == 1 ? "y" : "ies") + " " + inTime;
                },
                r -> "Failed to resolve dependencies " + BuildCommand.inTime(r));

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache, spec);
        if (!result.success()) {
            String failed = result.phases().stream()
                    .filter(p -> p.status() == PhaseStatus.FAIL)
                    .map(GoalResult.PhaseReport::name).findFirst().orElse("?");
            return failed.equals("resolve") ? 6 : 2;
        }
        return 0;
    }

    /**
     * Resolve the project's {@code kotlin} version selector to a concrete
     * Kotlin compiler release, the same way a dependency version is resolved:
     * an {@code =}-pin short-circuits; a floating selector is matched against
     * the versions of {@code kotlin-compiler-embeddable} on Maven Central, and
     * the highest match wins. Returns {@code null} for a Java project, or when
     * resolution can't complete (offline with nothing cached, or no match) —
     * the build then falls back to its default Kotlin version.
     */
    private static String resolveKotlinVersion(JkBuild effective, RepoGroup repos) {
        if (!effective.project().isKotlin()) return null;
        VersionSelector selector = effective.project().kotlin();
        if (selector instanceof VersionSelector.Exact exact) {
            return exact.version();
        }
        VersionSet set = VersionSelectors.toVersionSet(selector);
        Coordinate coord = Coordinate.of(
                "org.jetbrains.kotlin", "kotlin-compiler-embeddable", "any");
        List<String> available;
        try {
            available = repos.availableVersions(coord);
        } catch (java.io.IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        // Prefer the highest stable match; fall back to a pre-release only when
        // no stable version satisfies (consistent with dependency resolution).
        return available.stream()
                .filter(set::contains)
                .filter(Versions::isStable)
                .max(Versions::compare)
                .or(() -> available.stream().filter(set::contains).max(Versions::compare))
                .orElse(null);
    }

    /**
     * Throw if an existing lockfile can't be honored entirely from the local
     * CAS while offline: every declared (non-platform) coordinate must be a
     * locked package, and every checksummed package's blob must be present.
     */
    private static void requireOfflineSatisfiable(JkBuild effective, Lockfile lock, Cas cas) {
        java.util.Set<String> locked = new java.util.HashSet<>();
        for (Lockfile.Package pkg : lock.packages()) {
            locked.add(pkg.name());
        }
        for (var entry : effective.dependencies().byScope().entrySet()) {
            if (entry.getKey() == Scope.PLATFORM) continue; // BOMs aren't resolved packages
            for (var dep : entry.getValue()) {
                if (!locked.contains(dep.module())) {
                    throw new IllegalStateException("offline: " + dep.module()
                            + " is declared in jk.toml but not in jk.lock; run `jk lock` online first");
                }
            }
        }
        for (Lockfile.Package pkg : lock.packages()) {
            String checksum = pkg.checksum();
            if (checksum == null) continue;
            String hex = checksum.startsWith("sha256:")
                    ? checksum.substring("sha256:".length()) : checksum;
            if (!cas.contains(hex)) {
                throw new IllegalStateException("offline: " + pkg.name() + ":" + pkg.version()
                        + " is locked but its artifact isn't cached; run `jk sync` online first");
            }
        }
    }
}
