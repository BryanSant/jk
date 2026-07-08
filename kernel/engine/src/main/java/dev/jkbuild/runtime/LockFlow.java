// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.jdk.JavaHomes;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.WorkspaceMerge;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.LockOrchestrator;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared "resolve jk.toml → write jk.lock" pipeline used by both {@code jk lock} and {@code jk
 * sync} (the latter delegating here when no lockfile exists yet). This is pure logic: failures are
 * returned in {@link Result#error} for the caller to surface — nothing is written to {@code stderr}
 * here, so only the CLI view layer touches the streams.
 */
public final class LockFlow {

    private LockFlow() {}

    /**
     * Outcome of one lock pass. {@code status == 0} means success, {@link #error} is {@code null},
     * and {@link #lockfile} / {@link #build} are populated. Non-zero means the caller should return
     * that exit code and surface {@link #error} (a bare message, no verb prefix).
     */
    public record Result(int status, String error, Lockfile lockfile, JkBuild build, int workspaceModuleCount) {}

    /** Run the lock pipeline against {@code dir}. */
    public static Result run(Path dir, Path cache, List<String> features, boolean noDefaultFeatures, URI repoUrl)
            throws Exception {
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            return new Result(2, "no jk.toml in " + dir, null, null, 0);
        }
        Files.createDirectories(cache);

        JkBuild parsed;
        try {
            parsed = JkBuildParser.parse(buildFile);
        } catch (RuntimeException e) {
            return new Result(2, e.getMessage(), null, null, 0);
        }

        // Workspace context: two cases.
        //
        //   1. parsed IS the workspace root → merge every module's deps
        //      into the root and lock the whole thing as one (PRD §13.2).
        //   2. parsed is a module of an enclosing workspace → resolve any
        //      `workspace:*` placeholders and filter out coords that
        //      match a sibling. WorkspaceClasspath at compile time will
        //      inject sibling jars from the shared target/.
        //
        // Both paths run before RepoGroupBuilder so the dep list reaching
        // the resolver contains only external Maven coords.
        JkBuild effective = parsed;
        int moduleCount = 0;
        try {
            if (parsed.isWorkspaceRoot()) {
                var modules = WorkspaceLoader.loadModules(dir, parsed);
                effective = WorkspaceMerge.merge(parsed, modules.values());
                moduleCount = modules.size();
            } else {
                var rootOpt = WorkspaceLocator.findRoot(dir);
                if (rootOpt.isPresent()) {
                    JkBuild rootManifest = JkBuildParser.parse(rootOpt.get().resolve("jk.toml"));
                    var modules = WorkspaceLoader.loadModules(rootOpt.get(), rootManifest);
                    effective = WorkspaceMerge.applyToModule(rootManifest, parsed, modules.values());
                    moduleCount = modules.size();
                }
            }
        } catch (RuntimeException e) {
            return new Result(2, e.getMessage(), null, null, 0);
        }

        Cas cas = new Cas(cache);
        RepoGroup baseRepos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);

        // Git-source deps: materialize each into a local file:// repo and rewrite
        // them to exact coordinate pins before the solver runs (git-source-deps.md).
        GitSourceResolution.Prepared prep;
        try {
            prep = GitSourceResolution.prepare(
                    effective,
                    baseRepos,
                    cas,
                    JavaHomes.resolveJavaHome(dir),
                    dev.jkbuild.util.JkVersion.VERSION);
        } catch (Exception e) {
            return new Result(6, e.getMessage(), null, effective, moduleCount);
        }
        LockOrchestrator orchestrator = new LockOrchestrator(prep.repos());

        Lockfile lock;
        try {
            lock = orchestrator.lock(prep.project(), dev.jkbuild.util.JkVersion.VERSION, features, !noDefaultFeatures);
        } catch (IOException e) {
            return new Result(6, e.getMessage(), null, effective, moduleCount);
        }
        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
        LockfileWriter.write(lock, lockFile);
        dev.jkbuild.task.AccessLedger.atDefaultPath().touchLock(lock);
        return new Result(0, null, lock, effective, moduleCount);
    }
}
