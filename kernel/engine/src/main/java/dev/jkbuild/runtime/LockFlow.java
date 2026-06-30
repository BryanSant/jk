// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.WorkspaceMerge;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.LockOrchestrator;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
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

        // Stat-check deferred path deps: string shorthands that weren't prefixed with
        // '.', '/', 'git://', or 'https://' are stored as path deps whose source may be
        // a bare relative path like "some-dir". Confirm each resolves to a directory now
        // so the user gets a clear error rather than a silent build failure later.
        try {
            effective = statDeferredPaths(effective, dir);
        } catch (IllegalArgumentException e) {
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
                    CompileToolchain.resolveJavaHome(dir),
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

    /**
     * Validates "deferred" path dependencies produced by the string-shorthand parser from values
     * that were neither an explicit path prefix ({@code .}, {@code /}) nor a git URL nor a
     * version spec. Their {@code pathSource} is a bare relative string like {@code "some-dir"};
     * we stat {@code projectDir.resolve(pathSource)} now and error loudly if it is not a
     * directory.
     *
     * <p>Returns {@code project} unchanged when there are no deferred deps (the common case).
     */
    private static JkBuild statDeferredPaths(JkBuild project, Path projectDir) {
        boolean anyDeferred = false;
        for (Scope scope : Scope.values()) {
            for (Dependency dep : project.dependencies().of(scope)) {
                if (isDeferred(dep)) {
                    anyDeferred = true;
                    break;
                }
            }
            if (anyDeferred) break;
        }
        if (!anyDeferred) return project;

        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            List<Dependency> deps = project.dependencies().of(scope);
            if (deps.isEmpty()) continue;
            List<Dependency> out = new ArrayList<>(deps.size());
            for (Dependency dep : deps) {
                if (isDeferred(dep)) {
                    Path candidate = projectDir.resolve(dep.pathSource());
                    if (!Files.isDirectory(candidate)) {
                        throw new IllegalArgumentException("dep '"
                                + dep.library()
                                + "' = \""
                                + dep.pathSource()
                                + "\" is not an existing directory"
                                + " (prefix with './' for an explicit relative path)");
                    }
                }
                out.add(dep);
            }
            byScope.put(scope, out);
        }
        return new JkBuild(
                project.project(),
                new JkBuild.Dependencies(byScope),
                project.repositories(),
                project.profiles(),
                project.features(),
                project.workspace(),
                project.manifest(),
                project.plugins(),
                project.nativeConfig(),
                project.build(),
                project.format());
    }

    /**
     * A dep is "deferred" when it is a path dep whose {@code pathSource} was not an explicit
     * path prefix ({@code .} or {@code /}) — i.e., it came from an ambiguous string shorthand
     * and needs a filesystem stat to confirm it names a directory.
     */
    private static boolean isDeferred(Dependency dep) {
        if (!dep.isPath()) return false;
        String src = dep.pathSource();
        return !src.startsWith(".") && !src.startsWith("/");
    }
}
