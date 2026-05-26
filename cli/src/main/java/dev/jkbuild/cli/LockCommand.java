// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code jk lock} — resolve declared dependencies and write {@code jk.lock}.
 *
 * <p>Reads repositories + features from the project's jk.toml. Feature
 * selection: {@code --features=A,B} adds those features on top of the
 * declared {@code features.default}; {@code --no-default-features}
 * disables the default list entirely. Cargo semantics.
 *
 * <p>Three phases: {@code parse-build} (SYNC) reads jk.toml and
 * (optionally) merges workspace members; {@code resolve} (IO) drives
 * the {@link LockOrchestrator} — this is where network/CAS work happens;
 * {@code write-lockfile} (SYNC) serialises the result.
 */
@Command(name = "lock", description = "Resolve declared dependencies and write jk.lock")
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
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");

        if (!Files.exists(buildFile)) {
            System.err.println("jk lock: no jk.toml in " + dir);
            return 2;
        }
        Files.createDirectories(cache);

        AtomicInteger memberCount = new AtomicInteger(0);

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
                            memberCount.set(members.size());
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
                    ctx.label("resolve dependencies");
                    JkBuild effective = ctx.require(EFFECTIVE);
                    RepoGroup repos = RepoGroupBuilder.buildFor(effective, repoUrl, new Cas(cache));
                    LockOrchestrator orchestrator = new LockOrchestrator(repos);
                    try {
                        Lockfile lock = orchestrator.lock(
                                effective, Jk.VERSION, features, !noDefaultFeatures);
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

        Goal goal = Goal.builder("lock")
                .addPhase(parseBuild)
                .addPhase(resolve)
                .addPhase(write)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) {
            String failed = result.phases().stream()
                    .filter(p -> p.status() == PhaseStatus.FAIL)
                    .map(GoalResult.PhaseReport::name).findFirst().orElse("?");
            System.err.println("jk lock failed: " + failed);
            for (GoalResult.Diagnostic d : result.errors()) {
                System.err.println("  " + d.code() + ": " + d.message());
            }
            System.err.println("Run log: " + cache.resolve("runs"));
            return failed.equals("resolve") ? 6 : 2;
        }

        if (memberCount.get() > 0) {
            System.out.println("Workspace: " + memberCount.get() + " member"
                    + (memberCount.get() == 1 ? "" : "s"));
        }
        Lockfile lock = goal.get(LOCKFILE).orElseThrow();
        System.out.println("Wrote " + lockFile + " (" + lock.packages().size() + " package"
                + (lock.packages().size() == 1 ? "" : "s") + ")");
        return 0;
    }
}
