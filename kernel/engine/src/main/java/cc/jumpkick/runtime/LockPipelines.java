// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.LockOrchestrator;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.resolver.VersionSelectors;
import cc.jumpkick.resolver.Versions;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepKind;
import cc.jumpkick.run.StepNames;
import cc.jumpkick.run.StepStatus;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * The shared "resolve one project → write its {@code jk.lock}" pipeline used by {@code jk lock} and
 * {@code jk update} — hoisted out of the CLI so the resident engine can host both commands (Wave 1 of
 * {@code docs/architecture/slim-client.md}) while the commands' test-only in-process path builds the
 * exact same pipeline. Like {@link LockFlow} (the listener-less variant {@code jk sync}/{@code jk build}
 * auto-lock through), nothing here writes to stdout/stderr; progress flows through the pipeline's
 * listeners and the supplied {@link ResolveObserver}.
 *
 * <p><b>Rendering rule</b> (re-foundation): diagnostics and conflict prose produced here are plain
 * uncolored text — no {@code Diagnostics.Palette} is ever injected into the resolver anymore (the
 * CLI used to pre-theme conflict messages with its dark-theme palette; hosted diagnostics must cross
 * the wire unthemed). Any colorization happens client-side in the renderer. The optional {@code
 * coordLabel} formatter exists for the same reason in reverse: the in-process CLI path passes its
 * themed coordinate formatter so labels render exactly as before, while the engine passes {@code
 * null} and streams structured {@code lock-package} events for the client to format itself.
 */
public final class LockPipelines {

    private LockPipelines() {}

    /** Cross-step key: the effective (workspace-merged) manifest the resolve step reads. */
    public static final PipelineKey<JkBuild> EFFECTIVE = PipelineKey.of("effective-build", JkBuild.class);

    /** Cross-step key: the lockfile as it accumulates through resolve → lock-plugins → write. */
    public static final PipelineKey<Lockfile> LOCKFILE = PipelineKey.of("lockfile", Lockfile.class);

    /**
     * Build the {@code jk lock} pipeline for one project directory: {@code parse-build} → {@code
     * resolve} (offline-aware, git-source materialization, PubGrub solve, kotlin pin) → {@code
     * lock-plugins} → {@code write-lockfile}. The offline flag is read off the ambient {@link
     * SessionContext} at step-run time, so both the CLI (which installs the session from its
     * global flags) and the engine (which reconstructs it from the wire request) behave alike.
     *
     * @param observer per-package resolution events (never {@code null}; use {@link
     *     ResolveObserver#NOOP})
     * @param coordLabel formats a {@code module, version} pair for progress labels, or {@code null}
     *     to emit no per-package labels (the engine-hosted path — the client synthesizes them from
     *     {@code lock-package} events so coloring stays client-side)
     */
    public static Pipeline lockPipeline(
            Path dir,
            JkBuild effective,
            Path cache,
            URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            boolean sources,
            ResolveObserver observer,
            BiFunction<String, String, String> coordLabel) {
        Path lockFile = dir.resolve("jk.lock");
        AtomicInteger resolveEstimate = new AtomicInteger(0);

        Step parseBuild = Step.builder(StepNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    ctx.put(EFFECTIVE, effective);
                    ctx.progress(1);
                })
                .build();

        Step resolve = Step.builder(StepNames.RESOLVE_DEPS)
                .phase(Phase.RESOLVE)
                .label("Resolving")
                .kind(StepKind.IO)
                .requires(StepNames.PARSE_BUILD)
                .ticks(() -> {
                    int estimate = scopeEstimate(effective, lockFile);
                    resolveEstimate.set(estimate);
                    return estimate;
                })
                .execute(ctx -> {
                    ctx.label("Resolving");
                    JkBuild eff = ctx.require(EFFECTIVE);
                    Cas cas = new Cas(cache);
                    if (SessionContext.current().offline() && Files.exists(lockFile)) {
                        try {
                            Lockfile existing = LockfileReader.read(lockFile);
                            requireOfflineSatisfiable(eff, existing, cas);
                            ctx.progress(existing.artifacts().size());
                            ctx.put(LOCKFILE, existing);
                            return;
                        } catch (Exception e) {
                            ctx.error(StepNames.RESOLVE_DEPS, e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }
                    RepoGroup baseRepos = RepoGroupBuilder.buildFor(eff, repoUrl, cas);
                    Map<String, String> lockedShas = Map.of();
                    if (Files.exists(lockFile)) {
                        try {
                            lockedShas = GitSourceResolution.lockedImmutableShas(LockfileReader.read(lockFile));
                        } catch (Exception ignored) {
                        }
                    }
                    GitSourceResolution.Prepared prep;
                    PathSourceResolution.Prepared pathPrep;
                    try {
                        Path javaHome = JavaHomes.resolveJavaHome(dir);
                        prep = GitSourceResolution.prepare(
                                eff, baseRepos, cas, javaHome, JkVersion.VERSION, lockedShas);
                        pathPrep = PathSourceResolution.prepare(
                                prep.project(), prep.repos(), cas, dir, javaHome, JkVersion.VERSION);
                    } catch (Exception e) {
                        ctx.error(StepNames.RESOLVE_DEPS, e.getMessage());
                        throw new RuntimeException(e);
                    }
                    RepoGroup repos = pathPrep.repos();
                    // Deliberately no Diagnostics.Palette here — see the class javadoc.
                    LockOrchestrator orchestrator = new LockOrchestrator(repos)
                            .withJvmEnvironment(cc.jumpkick.plugin.manifest.PluginContributions.jvmEnvironment(
                                    pathPrep.project(), dir));
                    // Wrap the caller's observer so it also drives ctx.label/progress
                    // (the bar under a console listener; wire progress events when hosted).
                    ResolveObserver wrappedObserver = new ResolveObserver() {
                        @Override
                        public void onTotal(int total) {
                            int delta = total - resolveEstimate.getAndSet(total);
                            if (delta > 0) ctx.updateTicks(delta);
                            observer.onTotal(total);
                        }

                        @Override
                        public void onPackage(String module, String version) {
                            if (coordLabel != null) {
                                ctx.label("Resolved " + coordLabel.apply(module, version));
                            }
                            ctx.progress(1);
                            observer.onPackage(module, version);
                        }
                    };
                    try {
                        Lockfile lock = sources
                                ? orchestrator.lockWithSources(
                                        pathPrep.project(),
                                        JkVersion.VERSION,
                                        features,
                                        withDefaultFeatures,
                                        wrappedObserver)
                                : orchestrator.lock(
                                        pathPrep.project(),
                                        JkVersion.VERSION,
                                        features,
                                        withDefaultFeatures,
                                        wrappedObserver);
                        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
                        String kotlinVersion = resolveKotlinVersion(eff, repos);
                        if (kotlinVersion != null) {
                            ctx.label("resolved kotlin " + kotlinVersion);
                            lock = lock.withKotlin(kotlinVersion);
                        }
                        ctx.put(LOCKFILE, lock);
                    } catch (UnsatisfiableException e) {
                        ctx.error("verbatim", e.getMessage());
                        throw new RuntimeException(e);
                    } catch (Exception e) {
                        ctx.error(StepNames.RESOLVE_DEPS, e.getMessage());
                        throw new RuntimeException(e);
                    }
                })
                .build();

        Step lockPlugins = Step.builder(StepNames.LOCK_PLUGINS)
                .kind(StepKind.IO)
                .requires(StepNames.RESOLVE_DEPS)
                .ticks(() ->
                        effective.plugins().isEmpty() ? 0 : effective.plugins().size())
                .execute(ctx -> {
                    var decls = effective.plugins();
                    if (decls.isEmpty()) return;
                    ctx.label("lock plugins");
                    Cas cas = new Cas(cache);
                    RepoGroup repos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);
                    var entries = new ArrayList<Lockfile.PluginEntry>();
                    for (var pd : decls) {
                        ctx.label("lock " + pd.coordinate());
                        var coord = Coordinate.of(pd.group(), pd.name(), pd.version());
                        try {
                            var fetched = repos.tryFetchArtifact(coord)
                                    .orElseThrow(() -> new RuntimeException(
                                            pd.coordinateWithVersion() + " not found in any repo"));
                            entries.add(new Lockfile.PluginEntry(
                                    pd.coordinate(),
                                    pd.version(),
                                    "sha256:" + fetched.fetched().sha256()));
                            try {
                                PluginDescriptorOps.materialize(
                                        dir,
                                        fetched.fetched().sha256(),
                                        fetched.fetched().cachePath());
                            } catch (java.io.IOException e) {
                                ctx.output("note: " + pd.coordinate() + " has no jk-plugin.toml — locked, but"
                                        + " it will not own a jk.toml table");
                            }
                        } catch (Exception e) {
                            ctx.error("plugin", pd.coordinate() + " — " + e.getMessage());
                            throw new RuntimeException(e);
                        }
                        ctx.progress(1);
                    }
                    ctx.put(LOCKFILE, ctx.require(LOCKFILE).withPlugins(entries));
                })
                .build();

        Step lockSdk = Step.builder(StepNames.LOCK_SDK)
                .kind(StepKind.IO)
                .requires(StepNames.LOCK_PLUGINS)
                .ticks(1)
                .execute(ctx -> {
                    // Provisioned-SDK revision pins (android-plan §3.2 — hermetic, lockfile-pinned
                    // components): recorded for every sdk-component a plugin manifest contributes.
                    // Installed components pin what is actually on disk; uninstalled ones pin the
                    // feed's stable revision when reachable, else stay unpinned until the first
                    // build installs them and the next lock records it. Provider-neutral shape.
                    java.util.LinkedHashSet<String> components = new java.util.LinkedHashSet<>();
                    try {
                        for (var sd :
                                cc.jumpkick.plugin.manifest.PluginContributions.stepDependencies(effective, dir)) {
                            if (sd.sdkComponent() != null && !"root".equals(sd.sdkComponent())) {
                                components.add(sd.sdkComponent());
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // no plugin tables / no contributions — nothing to pin
                    }
                    if (components.isEmpty()) return;
                    ctx.label("pin sdk components");
                    var entries = new ArrayList<cc.jumpkick.lock.Lockfile.SdkEntry>();
                    for (String component : components) {
                        String revision = SdkComponents.installedRevision(component);
                        if (revision == null) {
                            try {
                                var sdk = cc.jumpkick.androidsdk.AndroidSdk.resolve();
                                var feedComponent = new cc.jumpkick.androidsdk.AndroidSdkInstaller(sdk)
                                        .feed()
                                        .find(component);
                                if (feedComponent != null) revision = feedComponent.revision();
                            } catch (Exception ignored) {
                                // offline / feed unreachable — leave unpinned rather than guess
                            }
                        }
                        if (revision != null) {
                            entries.add(new cc.jumpkick.lock.Lockfile.SdkEntry(component, revision));
                        }
                    }
                    ctx.put(LOCKFILE, ctx.require(LOCKFILE).withSdk(entries));
                })
                .build();

        Step write = Step.builder(StepNames.WRITE_LOCKFILE)
                .requires(StepNames.LOCK_SDK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("write " + lockFile.getFileName());
                    LockfileWriter.write(ctx.require(LOCKFILE), lockFile);
                    ctx.progress(1);
                })
                .build();

        return Pipeline.builder("lock")
                .addStep(parseBuild)
                .addStep(resolve)
                .addStep(lockPlugins)
                .addStep(lockSdk)
                .addStep(write)
                .build();
    }

    /**
     * Build the {@code jk update} pipeline for one project directory: same pipeline as {@link
     * #lockPipeline}, but always resolving fresh (no offline shortcut, no lockfile-SHA canary — update
     * is the "accept the new commit" path; see {@code docs/git-source-deps.md}).
     */
    public static Pipeline updatePipeline(
            Path dir, JkBuild effective, Path cache, URI repoUrl, List<String> features, boolean withDefaultFeatures) {
        Path lockFile = dir.resolve("jk.lock");

        Step parseBuild = Step.builder(StepNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    ctx.put(EFFECTIVE, effective);
                    ctx.progress(1);
                })
                .build();

        Step resolve = Step.builder(StepNames.RESOLVE_DEPS)
                .phase(Phase.RESOLVE)
                .kind(StepKind.IO)
                .requires(StepNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("re-resolve dependencies");
                    JkBuild eff = ctx.require(EFFECTIVE);
                    Cas cas = new Cas(cache);
                    RepoGroup baseRepos = RepoGroupBuilder.buildFor(eff, repoUrl, cas);
                    try {
                        // Git-source deps: re-materialize against the current ref tip and accept
                        // any movement (no tag-rewrite check; see docs/git-source-deps.md).
                        Path javaHome = JavaHomes.resolveJavaHome(dir);
                        GitSourceResolution.Prepared prep =
                                GitSourceResolution.prepare(eff, baseRepos, cas, javaHome, JkVersion.VERSION);
                        PathSourceResolution.Prepared pathPrep = PathSourceResolution.prepare(
                                prep.project(), prep.repos(), cas, dir, javaHome, JkVersion.VERSION);
                        Lockfile lock = new LockOrchestrator(pathPrep.repos())
                                .withJvmEnvironment(cc.jumpkick.plugin.manifest.PluginContributions.jvmEnvironment(
                                        pathPrep.project(), dir))
                                .lock(pathPrep.project(), JkVersion.VERSION, features, withDefaultFeatures);
                        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
                        ctx.put(LOCKFILE, lock);
                    } catch (Exception e) {
                        ctx.error(StepNames.RESOLVE_DEPS, e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Step write = Step.builder(StepNames.WRITE_LOCKFILE)
                .requires(StepNames.RESOLVE_DEPS)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("write " + lockFile.getFileName());
                    LockfileWriter.write(ctx.require(LOCKFILE), lockFile);
                    ctx.progress(1);
                })
                .build();

        return Pipeline.builder("update")
                .addStep(parseBuild)
                .addStep(resolve)
                .addStep(write)
                .build();
    }

    // ---- jk update --git ----------------------------------------------------

    /**
     * Outcome of a {@code jk update --git} pass. {@code exitCode == 0} means success and {@code
     * refreshed} counts the git artifacts actually re-pinned; non-zero means the caller should
     * surface {@code error} (a bare, uncolored message — no command prefix) and exit with that code.
     */
    public record GitUpdateOutcome(int exitCode, int refreshed, String error) {}

    /**
     * {@code jk update --git [<name>]}: re-resolve git dependencies only, in {@code root}'s project
     * and (for a workspace root) each declared module — one dependency by its declared name, or
     * every git dependency when {@code targetLibrary} is {@code null}. Every scope with no matching
     * git dependency is left untouched entirely (its {@code jk.lock} isn't even read).
     */
    public static GitUpdateOutcome updateGitOnly(
            Path dir,
            JkBuild root,
            Path cache,
            URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            String targetLibrary)
            throws Exception {
        JkBuild effectiveRoot = applyWorkspaceContextIfModule(dir, root);
        var scopes = new java.util.LinkedHashMap<Path, JkBuild>();
        scopes.put(dir, effectiveRoot);
        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules;
            try {
                modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
            } catch (RuntimeException e) {
                return new GitUpdateOutcome(Exit.CONFIG, 0, e.getMessage());
            }
            for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                scopes.put(
                        entry.getKey(),
                        WorkspaceMerge.applyToModule(effectiveRoot, entry.getValue(), modules.values()));
            }
        }

        int totalRefreshed = 0;
        for (Map.Entry<Path, JkBuild> scope : scopes.entrySet()) {
            List<Dependency> gitDeps = declaredGitDeps(scope.getValue());
            List<Dependency> targeted = targetLibrary == null
                    ? gitDeps
                    : gitDeps.stream()
                            .filter(d -> d.library().equals(targetLibrary))
                            .toList();
            if (targeted.isEmpty()) continue;

            try {
                totalRefreshed += updateGitOnlyForScope(
                        scope.getKey(), scope.getValue(), cache, repoUrl, features, withDefaultFeatures, targeted);
            } catch (Exception e) {
                return new GitUpdateOutcome(6, 0, e.getMessage());
            }
        }

        if (targetLibrary != null && totalRefreshed == 0) {
            return new GitUpdateOutcome(Exit.CONFIG, 0, "no git dependency named `" + targetLibrary + "` found.");
        }
        return new GitUpdateOutcome(0, totalRefreshed, null);
    }

    /**
     * Re-resolve {@code effective}'s full dependency set (the normal pipeline — every git dep
     * accepts upstream movement, no tag-rewrite check), then splice the result against the existing
     * lock so only {@code targeted}'s git artifact(s) actually change; every other artifact keeps
     * its previously-locked value. Returns how many of {@code targeted} were actually refreshed.
     */
    private static int updateGitOnlyForScope(
            Path dir,
            JkBuild effective,
            Path cache,
            URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            List<Dependency> targeted)
            throws Exception {
        Path lockFile = dir.resolve("jk.lock");
        Lockfile oldLock = Files.exists(lockFile) ? LockfileReader.read(lockFile) : null;

        Cas cas = new Cas(cache);
        RepoGroup baseRepos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);
        Path javaHome = JavaHomes.resolveJavaHome(dir);
        GitSourceResolution.Prepared prep =
                GitSourceResolution.prepare(effective, baseRepos, cas, javaHome, JkVersion.VERSION);
        PathSourceResolution.Prepared pathPrep =
                PathSourceResolution.prepare(prep.project(), prep.repos(), cas, dir, javaHome, JkVersion.VERSION);
        Lockfile newLock = new LockOrchestrator(pathPrep.repos())
                .withJvmEnvironment(
                        cc.jumpkick.plugin.manifest.PluginContributions.jvmEnvironment(pathPrep.project(), dir))
                .lock(pathPrep.project(), JkVersion.VERSION, features, withDefaultFeatures);
        newLock = GitSourceResolution.stamp(newLock, prep.gitInfoByKey());

        java.util.Set<String> targetKeys = new java.util.LinkedHashSet<>();
        for (Dependency d : targeted) targetKeys.add(gitKey(d.gitSource()));

        Map<String, Lockfile.Artifact> oldByName = new java.util.LinkedHashMap<>();
        if (oldLock != null) for (Lockfile.Artifact a : oldLock.artifacts()) oldByName.put(a.name(), a);

        List<Lockfile.Artifact> spliced = new ArrayList<>();
        int refreshed = 0;
        for (Lockfile.Artifact a : newLock.artifacts()) {
            boolean isTargeted = a.git() != null
                    && targetKeys.contains(a.git().url() + "|" + a.git().ref());
            if (isTargeted) {
                spliced.add(a);
                refreshed++;
                continue;
            }
            Lockfile.Artifact old = oldByName.get(a.name());
            spliced.add(old != null ? old : a);
        }
        Lockfile finalLock = new Lockfile(
                newLock.version(),
                newLock.generatedBy(),
                newLock.resolutionAlgorithm(),
                newLock.jdk(),
                newLock.kotlin(),
                spliced,
                oldLock != null ? oldLock.plugins() : newLock.plugins());
        LockfileWriter.write(finalLock, lockFile);
        return refreshed;
    }

    private static String gitKey(GitSource s) {
        return s.canonicalUrl() + "|" + s.ref().token();
    }

    /** Every git-sourced dependency directly declared across all scopes, deduped by library name. */
    private static List<Dependency> declaredGitDeps(JkBuild project) {
        List<Dependency> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (List<Dependency> deps : project.dependencies().byScope().values()) {
            for (Dependency d : deps) {
                if (d.isGit() && seen.add(d.library())) out.add(d);
            }
        }
        return out;
    }

    // ---- shared helpers ------------------------------------------------------

    /**
     * Map a failed lock/update pipeline to its exit code: a failed {@code resolve} step (unsatisfiable
     * deps, unreachable repos) exits 6; anything else is a config problem ({@link Exit#CONFIG}).
     */
    public static int failureExitCode(PipelineResult result) {
        boolean resolveFailed = result.steps().stream()
                .filter(p -> p.status() == StepStatus.FAIL)
                .map(PipelineResult.StepReport::name)
                .anyMatch(StepNames.RESOLVE_DEPS::equals);
        return resolveFailed ? 6 : Exit.CONFIG;
    }

    /**
     * When invoked from a workspace module (not the root), discover the enclosing workspace and apply
     * module context: resolve {@code workspace:} placeholders and filter out sibling-internal dep
     * coords so the solver only sees external Maven coordinates. Returns {@code project} unchanged if
     * it is a workspace root or no enclosing workspace is found.
     */
    public static JkBuild applyWorkspaceContextIfModule(Path dir, JkBuild project) {
        if (project.isWorkspaceRoot()) return project;
        try {
            var rootOpt = WorkspaceLocator.findRoot(dir);
            if (rootOpt.isEmpty()) return cc.jumpkick.model.Variants.unionDependencies(project);
            Path wsRoot = rootOpt.get();
            JkBuild wsRootBuild = JkBuildParser.parse(wsRoot.resolve("jk.toml"));
            if (!wsRootBuild.isWorkspaceRoot()) return cc.jumpkick.model.Variants.unionDependencies(project);
            var siblings = WorkspaceLoader.loadModules(wsRoot, wsRootBuild);
            return WorkspaceMerge.applyToModule(wsRootBuild, project, siblings.values());
        } catch (Exception ignored) {
            return cc.jumpkick.model.Variants.unionDependencies(project);
        }
    }

    /**
     * Display coordinate for a module: {@code group:artifact} from its {@code [project]}, falling
     * back to the directory name.
     */
    public static String coordLabel(JkBuild build, Path dir) {
        try {
            var p = build.project();
            return p.group() + ":" + p.name();
        } catch (Exception e) {
            return dir.getFileName() == null
                    ? dir.toString()
                    : dir.getFileName().toString();
        }
    }

    /**
     * Best-effort ticks estimate for a module's resolve step: the existing lockfile size (re-run)
     * or declared deps × a transitive expansion factor.
     */
    private static int scopeEstimate(JkBuild effective, Path lockFile) {
        try {
            int n = LockfileReader.read(lockFile).artifacts().size();
            if (n > 0) return n;
        } catch (Exception ignored) {
        }
        try {
            int declared = effective.dependencies().byScope().values().stream()
                    .mapToInt(List::size)
                    .sum();
            return Math.max(5, declared * 8);
        } catch (Exception ignored) {
        }
        return 20;
    }

    /**
     * Resolve the project's {@code kotlin} version selector to a concrete Kotlin compiler release.
     * Returns {@code null} for a Java project or when resolution can't complete.
     */
    private static String resolveKotlinVersion(JkBuild effective, RepoGroup repos) {
        if (!effective.project().isKotlin()) return null;
        VersionSelector selector = effective.project().kotlin();
        if (selector instanceof VersionSelector.Exact exact) {
            return exact.version();
        }
        VersionSet set = VersionSelectors.toVersionSet(selector);
        Coordinate coord = Coordinate.of("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "any");
        List<String> available;
        try {
            available = repos.availableVersions(coord);
        } catch (java.io.IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return available.stream()
                .filter(set::contains)
                .filter(Versions::isStable)
                .max(Versions::compare)
                .or(() -> available.stream().filter(set::contains).max(Versions::compare))
                .orElse(null);
    }

    /**
     * Throw if an existing lockfile can't be honored entirely from the local CAS while offline.
     */
    private static void requireOfflineSatisfiable(JkBuild effective, Lockfile lock, Cas cas) {
        java.util.Set<String> locked = new java.util.HashSet<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            locked.add(pkg.name());
        }
        for (var entry : effective.dependencies().byScope().entrySet()) {
            if (entry.getKey() == Scope.PLATFORM) continue;
            for (var dep : entry.getValue()) {
                if (!locked.contains(dep.module())) {
                    throw new IllegalStateException("offline: "
                            + dep.module()
                            + " is declared in jk.toml but not in jk.lock; run `jk lock` online first");
                }
            }
        }
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            String checksum = pkg.checksum();
            if (checksum == null) continue;
            String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
            if (!cas.contains(hex)) {
                throw new IllegalStateException("offline: "
                        + pkg.name()
                        + ":"
                        + pkg.version()
                        + " is locked but its artifact isn't cached; run `jk sync` online first");
            }
        }
    }
}
