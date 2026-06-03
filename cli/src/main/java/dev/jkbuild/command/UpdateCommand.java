// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.config.WorkspaceRedirect;

import dev.jkbuild.runtime.RepoGroupBuilder;

import dev.jkbuild.runtime.GitSourceResolution;

import dev.jkbuild.runtime.CompileToolchain;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
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
import java.util.concurrent.Callable;

/**
 * {@code jk update} — re-resolve declared dependencies and overwrite
 * {@code jk.lock}. Same pipeline as {@code jk lock}; the difference is
 * intent: {@code lock} is "make sure a lock exists", {@code update} is
 * "throw away whatever I have and resolve fresh."
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
        Path invokedDir = global.workingDir();
        if (!Files.exists(invokedDir.resolve("jk.toml"))) {
            System.err.println("jk update: no jk.toml in " + invokedDir);
            return 2;
        }
        // Re-resolve the whole workspace into the root jk.lock: when invoked
        // from a member, redirect to the enclosing workspace root (Cargo/uv).
        Path dir = WorkspaceRedirect.effectiveDir(invokedDir);
        if (!dir.equals(invokedDir) && !global.outputIsJson()) {
            System.err.println("jk update: updating workspace root " + dir
                    + " (from member " + invokedDir.getFileName() + ")");
        }
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (precise != null && !precise.isBlank()) {
            System.err.println("jk update: --precise is recognized but not yet implemented; "
                    + "performing a full re-resolve instead.");
        }

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    JkBuild parsed;
                    try {
                        parsed = JkBuildParser.parse(buildFile);
                    } catch (RuntimeException e) {
                        ctx.error("toml", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    JkBuild effective = parsed;
                    if (parsed.isWorkspaceRoot()) {
                        ctx.label("merge workspace members");
                        try {
                            var members = WorkspaceLoader.loadMembers(dir, parsed);
                            effective = WorkspaceMerge.merge(parsed, members.values());
                        } catch (RuntimeException e) {
                            ctx.error("workspace", e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }
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
                    JkBuild effective = ctx.require(EFFECTIVE);
                    Cas cas = new Cas(cache);
                    RepoGroup baseRepos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);
                    try {
                        // Git-source deps: re-materialize against the current ref
                        // tip and accept any movement (update is the "accept the
                        // new commit" path — no tag-rewrite check; see
                        // docs/git-source-deps.md).
                        GitSourceResolution.Prepared prep = GitSourceResolution.prepare(
                                effective, baseRepos, cas,
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
