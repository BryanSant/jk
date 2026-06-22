// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.runtime.RepoGroupBuilder;

import dev.jkbuild.runtime.GitSourceResolution;

import dev.jkbuild.runtime.CompileToolchain;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.PathDisplay;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.git.GitFetcher;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.WorkspaceMerge;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.LockOrchestrator;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.util.JkDirs;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code jk update} — re-resolve declared dependencies and overwrite
 * {@code jk.lock}. Same pipeline as {@code jk lock}; the difference is
 * intent: {@code lock} is "make sure a lock exists", {@code update} is
 * "throw away whatever I have and resolve fresh."
 *
 * <p>For workspace roots, updating cascades to each declared module in
 * declaration order, writing a fresh {@code jk.lock} alongside each
 * module's {@code jk.toml}.
 *
 * <p>{@code --precise &lt;coord&gt;@&lt;ver&gt;} per PRD §6 is accepted
 * but a no-op until selective resolution lands.
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
                Opt.value("<coord>@<ver>",
                        "Pin a single coord to a version for this update (not yet implemented).", "--precise"),
                Opt.value("<a,b,...>", "Activate the listed features in addition to defaults.", "--features")
                        .splitOn(","),
                Opt.flag("Don't activate the project's default features.", "--no-default-features"),
                Opt.flag("Re-fetch branch git dependencies' tips now (ignore the freshness window).", "--git"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url").hide(),
                Opt.value("<dir>", "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                        "--cache-dir").hide());
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

        // `jk update --git`: force branch git deps to re-resolve their tip on the
        // next build (clears the freshness stamp), instead of a dependency re-lock.
        if (in.isSet("git")) {
            return refreshGitBranches(dir, root, cache);
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
                JkBuild effectiveModule = WorkspaceMerge.applyToModule(
                        effectiveRoot, rawModule, modules.values());
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
                                eff, baseRepos, cas,
                                CompileToolchain.resolveJavaHome(dir), Jk.VERSION);
                        Lockfile lock = new LockOrchestrator(prep.repos()).lock(
                                prep.project(), Jk.VERSION, features, !noDefaultFeatures);
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
                    .map(GoalResult.PhaseReport::name).findFirst().orElse("?");
            return failed.equals("resolve") ? 6 : 2;
        }

        Lockfile lock = goal.get(LOCKFILE).orElseThrow();
        if (!global.outputIsJson()) {
            var th = Theme.active();
            int n = lock.artifacts().size();
            System.out.println(Theme.colorize(Glyphs.CHECK, th.success())
                    + " Updated: " + Theme.colorize(PathDisplay.of(lockFile, global.workingDir()), th.path())
                    + " " + Theme.colorize("›", th.darkGray())
                    + " " + Theme.colorize(String.valueOf(n), th.cyan())
                    + " package" + (n == 1 ? "" : "s"));
        }
        return 0;
    }

    /**
     * {@code jk update --git}: clear the branch-tip freshness stamp for every
     * branch git dependency (project + workspace modules), so the next build
     * re-resolves the remote tip regardless of the freshness window.
     */
    private int refreshGitBranches(Path dir, JkBuild root, Path cache) throws Exception {
        List<JkBuild> builds = new java.util.ArrayList<>();
        builds.add(root);
        if (root.isWorkspaceRoot()) builds.addAll(WorkspaceLoader.loadModules(dir, root).values());

        GitFetcher fetcher = new GitFetcher(cache.resolve("git"));
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        int n = 0;
        for (JkBuild b : builds) {
            for (List<Dependency> deps : b.dependencies().byScope().values()) {
                for (Dependency d : deps) {
                    if (d.isGit() && d.gitSource().ref() instanceof GitRefSpec.Branch
                            && seen.add(d.module() + "@" + d.gitSource().canonicalUrl())) {
                        fetcher.invalidateBranchTip(d.gitSource());
                        n++;
                    }
                }
            }
        }
        if (!global.outputIsJson()) {
            System.out.println(n == 0
                    ? "No branch git dependencies to refresh."
                    : "Marked " + n + " branch git dependenc" + (n == 1 ? "y" : "ies")
                            + " for re-fetch on the next build.");
        }
        return 0;
    }
}
