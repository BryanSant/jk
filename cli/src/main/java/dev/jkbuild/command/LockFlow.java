// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
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
 * Shared "resolve jk.toml → write jk.lock" pipeline used by both
 * {@code jk lock} and {@code jk sync} (the latter delegating here when no
 * lockfile exists yet). Error output is written to {@code stderr} with the
 * caller's command label so the user sees {@code "jk lock: ..."} vs
 * {@code "jk sync: ..."}.
 */
final class LockFlow {

    private LockFlow() {}

    /**
     * Outcome of one lock pass. {@code status == 0} means success and
     * {@link #lockfile} / {@link #build} are populated; non-zero means the
     * caller should return that exit code.
     */
    record Result(int status, Lockfile lockfile, JkBuild build, int workspaceMemberCount) {}

    /**
     * Run the lock pipeline against {@code dir}. {@code cmdLabel} is the
     * verb prefix used in error messages (e.g. {@code "jk lock"} or
     * {@code "jk sync"}).
     */
    static Result run(
            Path dir,
            Path cache,
            List<String> features,
            boolean noDefaultFeatures,
            URI repoUrl,
            String cmdLabel) throws Exception {
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println(cmdLabel + ": no jk.toml in " + dir);
            return new Result(2, null, null, 0);
        }
        Files.createDirectories(cache);

        JkBuild parsed;
        try {
            parsed = JkBuildParser.parse(buildFile);
        } catch (RuntimeException e) {
            System.err.println(cmdLabel + ": " + e.getMessage());
            return new Result(2, null, null, 0);
        }

        // Workspace context: two cases.
        //
        //   1. parsed IS the workspace root → merge every member's deps
        //      into the root and lock the whole thing as one (PRD §13.2).
        //   2. parsed is a member of an enclosing workspace → resolve any
        //      `workspace:*` placeholders and filter out coords that
        //      match a sibling. WorkspaceClasspath at compile time will
        //      inject sibling jars from the shared target/.
        //
        // Both paths run before RepoGroupBuilder so the dep list reaching
        // the resolver contains only external Maven coords.
        JkBuild effective = parsed;
        int memberCount = 0;
        try {
            if (parsed.isWorkspaceRoot()) {
                var members = WorkspaceLoader.loadMembers(dir, parsed);
                effective = WorkspaceMerge.merge(parsed, members.values());
                memberCount = members.size();
            } else {
                var rootOpt = WorkspaceLocator.findRoot(dir);
                if (rootOpt.isPresent()) {
                    JkBuild rootManifest = JkBuildParser.parse(
                            rootOpt.get().resolve("jk.toml"));
                    var members = WorkspaceLoader.loadMembers(rootOpt.get(), rootManifest);
                    effective = WorkspaceMerge.applyToMember(
                            rootManifest, parsed, members.values());
                    memberCount = members.size();
                }
            }
        } catch (RuntimeException e) {
            System.err.println(cmdLabel + ": " + e.getMessage());
            return new Result(2, null, null, 0);
        }

        Cas cas = new Cas(cache);
        RepoGroup baseRepos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);

        // Git-source deps: materialize each into a local file:// repo and rewrite
        // them to exact coordinate pins before the solver runs (git-source-deps.md).
        GitSourceResolution.Prepared prep;
        try {
            prep = GitSourceResolution.prepare(
                    effective, baseRepos, cas, CompileToolchain.resolveJavaHome(dir), Jk.VERSION);
        } catch (Exception e) {
            System.err.println(cmdLabel + ": " + e.getMessage());
            return new Result(6, null, effective, memberCount);
        }
        LockOrchestrator orchestrator = new LockOrchestrator(prep.repos());

        Lockfile lock;
        try {
            lock = orchestrator.lock(prep.project(), Jk.VERSION, features, !noDefaultFeatures);
        } catch (IOException e) {
            System.err.println(cmdLabel + ": " + e.getMessage());
            return new Result(6, null, effective, memberCount);
        }
        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
        LockfileWriter.write(lock, lockFile);
        return new Result(0, lock, effective, memberCount);
    }
}
