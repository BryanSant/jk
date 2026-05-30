// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
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
        Path invokedDir = global.workingDir();
        if (!Files.exists(invokedDir.resolve("jk.toml"))) {
            System.err.println("jk lock: no jk.toml in " + invokedDir);
            return 2;
        }
        // Lock the whole workspace into the root jk.lock: when invoked from
        // a member, redirect to the enclosing workspace root (Cargo/uv).
        Path dir = WorkspaceRedirect.effectiveDir(invokedDir);
        if (!dir.equals(invokedDir) && !global.outputIsJson()) {
            System.err.println("jk lock: locking workspace root " + dir
                    + " (from member " + invokedDir.getFileName() + ")");
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
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

        // Captured by both the scope supplier and the onTotal callback so the
        // phase body only adds the delta between the up-front estimate and
        // the actual package count, instead of adding the full actual count.
        java.util.concurrent.atomic.AtomicInteger resolveEstimate =
                new java.util.concurrent.atomic.AtomicInteger(0);

        Phase resolve = Phase.builder("resolve")
                .label("Resolving")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .scope(() -> {
                    // Best case: existing lockfile is accurate (re-runs).
                    try {
                        int n = dev.jkbuild.lock.LockfileReader.read(lockFile).packages().size();
                        if (n > 0) { resolveEstimate.set(n); return n; }
                    } catch (Exception ignored) {}
                    // Fallback: declared deps × rough transitive expansion.
                    try {
                        int declared = JkBuildParser.parse(buildFile)
                                .dependencies().byScope().values().stream()
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
                    JkBuild effective = ctx.require(EFFECTIVE);
                    Cas cas = new Cas(cache);
                    // Offline fast path: if a lockfile exists, honor it from
                    // the local CAS instead of re-solving. Hard-fail (no
                    // partial locks) when the cache can't satisfy it. With no
                    // lockfile we fall through to a normal solve, which the
                    // journal-backed repos serve offline automatically.
                    if (global.offline && Files.exists(lockFile)) {
                        try {
                            Lockfile existing = LockfileReader.read(lockFile);
                            requireOfflineSatisfiable(effective, existing, cas);
                            ctx.progress(existing.packages().size());
                            ctx.put(LOCKFILE, existing);
                            return;
                        } catch (Exception e) {
                            ctx.error("resolve", e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }
                    RepoGroup repos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);
                    LockOrchestrator orchestrator = new LockOrchestrator(repos);
                    dev.jkbuild.resolver.ResolveObserver observer =
                            new dev.jkbuild.resolver.ResolveObserver() {
                        @Override
                        public void onTotal(int total) {
                            // Only add the delta so the pre-allocated estimate
                            // slots aren't double-counted in the denominator.
                            int delta = total - resolveEstimate.get();
                            if (delta > 0) ctx.updateScope(delta);
                        }
                        @Override
                        public void onPackage(String module, String version) {
                            int colon = module.indexOf(':');
                            String group    = module.substring(0, colon);
                            String artifact = module.substring(colon + 1);
                            String label = "Resolved "
                                    + dev.jkbuild.cli.tui.Theme.colorize(group,
                                            dev.jkbuild.cli.tui.Theme.activeStep())
                                    + ":"
                                    + dev.jkbuild.cli.tui.Theme.colorize(artifact,
                                            dev.jkbuild.cli.tui.Theme.activeStep().bold())
                                    + ":"
                                    + dev.jkbuild.cli.tui.Theme.colorize(version,
                                            dev.jkbuild.cli.tui.Theme.warning());
                            ctx.label(label);
                            ctx.progress(1);
                        }
                    };
                    try {
                        Lockfile lock = orchestrator.lock(
                                effective, Jk.VERSION, features, !noDefaultFeatures, observer);
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

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) {
            String failed = result.phases().stream()
                    .filter(p -> p.status() == PhaseStatus.FAIL)
                    .map(GoalResult.PhaseReport::name).findFirst().orElse("?");
            return failed.equals("resolve") ? 6 : 2;
        }

        if (!global.outputIsJson()) {
            Lockfile lock = goal.get(LOCKFILE).orElseThrow();
            int pkgs = lock.packages().size();
            String check  = dev.jkbuild.cli.tui.Theme.colorize(
                    "✓", dev.jkbuild.cli.tui.Theme.brightGreen().bold());
            String inTime = dev.jkbuild.cli.tui.Theme.colorize(
                    "in " + BuildCommand.fmtDuration(result.duration()),
                    dev.jkbuild.cli.tui.Theme.darkGray());
            int members = memberCount.get();
            String suffix = members > 0
                    ? " across " + (members + 1) + " workspace"
                            + (members + 1 == 1 ? "" : "s")
                    : "";
            System.out.println(check + " Resolved " + pkgs + " dependenc"
                    + (pkgs == 1 ? "y" : "ies") + suffix + " " + inTime);
        }
        return 0;
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
