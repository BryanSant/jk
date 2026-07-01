// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.Jk;
import dev.jkbuild.cli.PathDisplay;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.WorkspaceMerge;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.LockOrchestrator;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.runtime.GitSourceResolution;
import dev.jkbuild.runtime.RepoGroupBuilder;
import dev.jkbuild.util.JkDirs;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code jk update} — re-resolve declared dependencies and overwrite {@code jk.lock}. Same pipeline
 * as {@code jk lock}; the difference is intent: {@code lock} is "make sure a lock exists", {@code
 * update} is "throw away whatever I have and resolve fresh."
 *
 * <p>For workspace roots, updating cascades to each declared module in declaration order, writing a
 * fresh {@code jk.lock} alongside each module's {@code jk.toml}.
 *
 * <p>{@code --precise &lt;coord&gt;@&lt;ver&gt;} per PRD §6 is accepted but a no-op until selective
 * resolution lands.
 *
 * <p>{@code --git [&lt;name&gt;]} re-resolves git dependencies only (one by name, or every git dep
 * when no name is given) — every ref type, tag/rev/branch alike, is pinned in {@code jk.lock} and
 * only moves forward here or via {@code jk fetch}. Every other dependency's locked version is left
 * exactly as it was: the full solver runs (reusing the normal resolve pipeline), then the result is
 * spliced against the previous lock so only the targeted git artifact(s) actually change.
 */
public final class UpdateCommand implements CliCommand {

    private String precise;
    private List<String> features = List.of();
    private boolean noDefaultFeatures;
    private URI repoUrl;
    private Path cacheDir;
    private GlobalOptions global;

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String description() {
        return "Propose version upgrades for declared dependencies";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<coord>@<ver>", "Pin one coord@ver (not yet implemented).", "--precise"),
                Opt.value("<a,b,...>", "Activate listed features beyond defaults.", "--features")
                        .splitOn(","),
                Opt.flag("Don't activate the project's defaults.", "--no-default-features"),
                Opt.value(
                                "[<name>]",
                                "Re-resolve one git dependency by its declared name, or every git"
                                        + " dependency when no name is given — leaving every other"
                                        + " dependency's locked version untouched.",
                                "--git")
                        .withFallback("*"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url")
                        .hide(),
                Opt.value(
                                "<dir>",
                                "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide());
    }

    private static final GoalKey<JkBuild> EFFECTIVE = GoalKey.of("effective-build", JkBuild.class);
    private static final GoalKey<Lockfile> LOCKFILE = GoalKey.of("lockfile", Lockfile.class);

    @Override
    public int run(Invocation in) throws Exception {
        this.precise = in.value("precise").orElse(null);
        this.features = in.values("features");
        this.noDefaultFeatures = in.isSet("no-default-features");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (!Files.exists(dir.resolve("jk.toml"))) {
            System.err.println("jk update: no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(dir));
            return 2;
        }
        if (precise != null && !precise.isBlank()) {
            System.err.println("jk update: --precise is recognized but not yet implemented; "
                    + "performing a full re-resolve instead.");
        }

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        JkBuild root;
        try {
            root = JkBuildParser.parse(dir.resolve("jk.toml"));
        } catch (RuntimeException e) {
            System.err.println("jk update: " + e.getMessage());
            return 2;
        }

        // `jk update --git [<name>]`: re-resolve git dependencies only, leaving every
        // other dependency's locked version untouched.
        if (in.has("git")) {
            String target = in.value("git").orElse("*");
            return updateGitOnly(dir, root, cache, "*".equals(target) ? null : target);
        }

        // When updating a workspace module directly, filter sibling-internal deps.
        JkBuild effectiveRoot = applyWorkspaceContextIfModule(dir, root);

        // Re-resolve the current directory (root or standalone project).
        int result = updateSingleProject(dir, effectiveRoot, cache);
        if (result != 0) return result;

        // Cascade: re-resolve each declared workspace module in declaration order.
        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules;
            try {
                modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
            } catch (RuntimeException e) {
                System.err.println("jk update: " + e.getMessage());
                return 2;
            }
            for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                Path moduleDir = entry.getKey();
                JkBuild rawModule = entry.getValue();
                JkBuild effectiveModule = WorkspaceMerge.applyToModule(effectiveRoot, rawModule, modules.values());
                int moduleResult = updateSingleProject(moduleDir, effectiveModule, cache);
                if (moduleResult != 0) return moduleResult;
            }
        }
        return 0;
    }

    private static JkBuild applyWorkspaceContextIfModule(Path dir, JkBuild project) {
        if (project.isWorkspaceRoot()) return project;
        try {
            var rootOpt = WorkspaceLocator.findRoot(dir);
            if (rootOpt.isEmpty()) return project;
            Path wsRoot = rootOpt.get();
            JkBuild wsRootBuild = JkBuildParser.parse(wsRoot.resolve("jk.toml"));
            if (!wsRootBuild.isWorkspaceRoot()) return project;
            var siblings = WorkspaceLoader.loadModules(wsRoot, wsRootBuild);
            return WorkspaceMerge.applyToModule(wsRootBuild, project, siblings.values());
        } catch (Exception ignored) {
            return project;
        }
    }

    private int updateSingleProject(Path dir, JkBuild effective, Path cache) throws Exception {
        Path lockFile = dir.resolve("jk.lock");

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    ctx.put(EFFECTIVE, effective);
                    ctx.progress(1);
                })
                .build();

        Phase resolve = Phase.builder("resolve")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("re-resolve dependencies");
                    JkBuild eff = ctx.require(EFFECTIVE);
                    Cas cas = new Cas(cache);
                    RepoGroup baseRepos = RepoGroupBuilder.buildFor(eff, repoUrl, cas);
                    try {
                        // Git-source deps: re-materialize against the current ref
                        // tip and accept any movement (update is the "accept the
                        // new commit" path — no tag-rewrite check; see
                        // docs/git-source-deps.md).
                        GitSourceResolution.Prepared prep = GitSourceResolution.prepare(
                                eff, baseRepos, cas, CompileToolchain.resolveJavaHome(dir), Jk.VERSION);
                        Lockfile lock = new LockOrchestrator(prep.repos())
                                .lock(prep.project(), Jk.VERSION, features, !noDefaultFeatures);
                        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
                        ctx.put(LOCKFILE, lock);
                    } catch (Exception e) {
                        ctx.error("resolve", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
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

        Goal goal = Goal.builder("update")
                .addPhase(parseBuild)
                .addPhase(resolve)
                .addPhase(write)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) {
            String failed = result.phases().stream()
                    .filter(p -> p.status() == PhaseStatus.FAIL)
                    .map(GoalResult.PhaseReport::name)
                    .findFirst()
                    .orElse("?");
            return failed.equals("resolve") ? 6 : 2;
        }

        Lockfile lock = goal.get(LOCKFILE).orElseThrow();
        if (!global.outputIsJson()) {
            var th = Theme.active();
            int n = lock.artifacts().size();
            System.out.println(Theme.colorize(Glyphs.CHECK, th.success())
                    + " Updated: "
                    + Theme.colorize(PathDisplay.of(lockFile, global.workingDir()), th.path())
                    + " "
                    + Theme.colorize("›", th.darkGray())
                    + " "
                    + Theme.colorize(String.valueOf(n), th.cyan())
                    + " package"
                    + (n == 1 ? "" : "s"));
        }
        return 0;
    }

    /**
     * {@code jk update --git [<name>]}: re-resolve git dependencies only, in the root project and
     * (for a workspace root) each declared module — one dependency by its declared name, or every
     * git dependency when {@code targetLibrary} is {@code null}. Every scope with no matching git
     * dependency is left untouched entirely (its {@code jk.lock} isn't even read).
     */
    private int updateGitOnly(Path dir, JkBuild root, Path cache, String targetLibrary) throws Exception {
        JkBuild effectiveRoot = applyWorkspaceContextIfModule(dir, root);
        var scopes = new java.util.LinkedHashMap<Path, JkBuild>();
        scopes.put(dir, effectiveRoot);
        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules;
            try {
                modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
            } catch (RuntimeException e) {
                System.err.println("jk update: " + e.getMessage());
                return 2;
            }
            for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                scopes.put(entry.getKey(), WorkspaceMerge.applyToModule(effectiveRoot, entry.getValue(), modules.values()));
            }
        }

        int totalRefreshed = 0;
        for (Map.Entry<Path, JkBuild> scope : scopes.entrySet()) {
            List<Dependency> gitDeps = declaredGitDeps(scope.getValue());
            List<Dependency> targeted = targetLibrary == null
                    ? gitDeps
                    : gitDeps.stream().filter(d -> d.library().equals(targetLibrary)).toList();
            if (targeted.isEmpty()) continue;

            int refreshed;
            try {
                refreshed = updateGitOnlyForScope(scope.getKey(), scope.getValue(), cache, targeted);
            } catch (Exception e) {
                System.err.println("jk update: " + e.getMessage());
                return 6;
            }
            totalRefreshed += refreshed;
        }

        if (targetLibrary != null && totalRefreshed == 0) {
            System.err.println("jk update: no git dependency named `" + targetLibrary + "` found.");
            return 2;
        }
        if (!global.outputIsJson()) {
            System.out.println(
                    totalRefreshed == 0
                            ? "No git dependencies to refresh."
                            : "Refreshed "
                                    + totalRefreshed
                                    + " git dependenc"
                                    + (totalRefreshed == 1 ? "y" : "ies")
                                    + ".");
        }
        return 0;
    }

    /**
     * Re-resolve {@code effective}'s full dependency set (the normal pipeline — every git dep
     * accepts upstream movement, no tag-rewrite check), then splice the result against the existing
     * lock so only {@code targeted}'s git artifact(s) actually change; every other artifact keeps
     * its previously-locked value. Returns how many of {@code targeted} were actually refreshed.
     */
    private int updateGitOnlyForScope(Path dir, JkBuild effective, Path cache, List<Dependency> targeted)
            throws Exception {
        Path lockFile = dir.resolve("jk.lock");
        Lockfile oldLock = Files.exists(lockFile) ? LockfileReader.read(lockFile) : null;

        Cas cas = new Cas(cache);
        RepoGroup baseRepos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);
        GitSourceResolution.Prepared prep = GitSourceResolution.prepare(
                effective, baseRepos, cas, CompileToolchain.resolveJavaHome(dir), Jk.VERSION);
        Lockfile newLock =
                new LockOrchestrator(prep.repos()).lock(prep.project(), Jk.VERSION, features, !noDefaultFeatures);
        newLock = GitSourceResolution.stamp(newLock, prep.gitInfoByKey());

        java.util.Set<String> targetKeys = new java.util.LinkedHashSet<>();
        for (Dependency d : targeted) targetKeys.add(gitKey(d.gitSource()));

        Map<String, Lockfile.Artifact> oldByName = new java.util.LinkedHashMap<>();
        if (oldLock != null) for (Lockfile.Artifact a : oldLock.artifacts()) oldByName.put(a.name(), a);

        List<Lockfile.Artifact> spliced = new java.util.ArrayList<>();
        int refreshed = 0;
        for (Lockfile.Artifact a : newLock.artifacts()) {
            boolean isTargeted = a.git() != null && targetKeys.contains(a.git().url() + "|" + a.git().ref());
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
        List<Dependency> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (List<Dependency> deps : project.dependencies().byScope().values()) {
            for (Dependency d : deps) {
                if (d.isGit() && seen.add(d.library())) out.add(d);
            }
        }
        return out;
    }
}
