// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.runtime.RepoGroupBuilder;

import dev.jkbuild.runtime.GitSourceResolution;

import dev.jkbuild.runtime.CompileToolchain;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
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
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code jk update} — re-resolve declared dependencies and overwrite
 * {@code jk.lock}. Same pipeline as {@code jk lock}; the difference is
 * intent: {@code lock} is "make sure a lock exists", {@code update} is
 * "throw away whatever I have and resolve fresh."
 *
 * <p>For workspace roots, updating cascades to each declared member in
 * declaration order, writing a fresh {@code jk.lock} alongside each
 * member's {@code jk.toml}.
 *
 * <p>{@code --precise &lt;coord&gt;@&lt;ver&gt;} per PRD §6 is accepted
 * but a no-op until selective resolution lands.
 */
@Command(name = "update", description = "Propose version upgrades for declared dependencies")
public final class UpdateCommand implements Callable<Integer> {

    @Option(names = "--precise", paramLabel = "<coord>@<ver>",
            description = "Pin a single coord to a version for this update (not yet implemented).")
    String precise;

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
            System.err.println("jk update: no jk.toml in " + dir);
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

        // When updating a workspace member directly, filter sibling-internal deps.
        JkBuild effectiveRoot = applyWorkspaceContextIfMember(dir, root);

        // Re-resolve the current directory (root or standalone project).
        int result = updateSingleProject(dir, effectiveRoot, cache);
        if (result != 0) return result;

        // Cascade: re-resolve each declared workspace member in declaration order.
        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> members;
            try {
                members = WorkspaceLoader.loadMembers(dir, effectiveRoot);
            } catch (RuntimeException e) {
                System.err.println("jk update: " + e.getMessage());
                return 2;
            }
            for (Map.Entry<Path, JkBuild> entry : members.entrySet()) {
                Path memberDir = entry.getKey();
                JkBuild rawMember = entry.getValue();
                JkBuild effectiveMember = WorkspaceMerge.applyToMember(
                        effectiveRoot, rawMember, members.values());
                int memberResult = updateSingleProject(memberDir, effectiveMember, cache);
                if (memberResult != 0) return memberResult;
            }
        }
        return 0;
    }

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
            System.out.println("Updated " + lockFile + " (" + lock.packages().size() + " package"
                    + (lock.packages().size() == 1 ? "" : "s") + ")");
        }
        return 0;
    }
}
