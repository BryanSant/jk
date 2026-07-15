// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.model.Variants;
import build.jumpkick.cache.Cas;
import build.jumpkick.config.JkBuildParser;
import build.jumpkick.config.WorkspaceLoader;
import build.jumpkick.config.WorkspaceLocator;
import build.jumpkick.jdk.JavaHomes;
import build.jumpkick.lock.Lockfile;
import build.jumpkick.lock.LockfileWriter;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.WorkspaceMerge;
import build.jumpkick.repo.RepoGroup;
import build.jumpkick.resolver.LockOrchestrator;
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
     * that exit code and surface {@link #error} (a bare message, no command prefix).
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
        // Standalone projects union variant dep overlays here; workspace scopes were unioned
        // inside WorkspaceMerge (idempotent either way).
        effective = Variants.unionDependencies(effective);

        Cas cas = new Cas(cache);
        RepoGroup baseRepos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);

        // Git- and path-source deps: materialize each into a local file:// repo and rewrite
        // them to exact coordinate pins before the solver runs (git-source-deps.md).
        GitSourceResolution.Prepared prep;
        PathSourceResolution.Prepared pathPrep;
        try {
            Path javaHome = JavaHomes.resolveJavaHome(dir);
            prep = GitSourceResolution.prepare(
                    effective, baseRepos, cas, javaHome, build.jumpkick.model.JkVersion.VERSION);
            pathPrep = PathSourceResolution.prepare(
                    prep.project(), prep.repos(), cas, dir, javaHome, build.jumpkick.model.JkVersion.VERSION);
        } catch (Exception e) {
            return new Result(6, e.getMessage(), null, effective, moduleCount);
        }
        LockOrchestrator orchestrator = new LockOrchestrator(pathPrep.repos())
                .withJvmEnvironment(
                        build.jumpkick.plugin.manifest.PluginContributions.jvmEnvironment(effective, dir));

        Lockfile lock;
        try {
            lock = orchestrator.lock(pathPrep.project(), build.jumpkick.model.JkVersion.VERSION, features, !noDefaultFeatures);
        } catch (IOException e) {
            return new Result(6, e.getMessage() + variantUnionHint(dir, parsed), null, effective, moduleCount);
        }
        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
        LockfileWriter.write(lock, lockFile);
        build.jumpkick.task.AccessLedger.atDefaultPath().touchLock(lock);
        return new Result(0, null, lock, effective, moduleCount);
    }

    /**
     * When a resolve fails and {@code [variants]} dependency overlays are in play, say so: the
     * lock resolves the UNION of every value's deps (docs/variants.md → Locking), so the conflict
     * may be between values that never build together — name each value's contributions so the
     * user can align versions across them.
     */
    private static String variantUnionHint(Path dir, JkBuild parsed) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try {
            collectOverlayLines(parsed, null, lines);
            if (parsed.isWorkspaceRoot()) {
                for (var e : WorkspaceLoader.loadModules(dir, parsed).entrySet()) {
                    collectOverlayLines(e.getValue(), e.getValue().project().name(), lines);
                }
            }
        } catch (Exception ignored) {
            // hint construction must never mask the real resolve error
        }
        if (lines.isEmpty()) return "";
        StringBuilder b = new StringBuilder(
                "\nnote: jk.lock resolves the UNION of every variant value's dependencies,"
                        + "\nso this conflict may be between values that never build together — align their"
                        + "\nversions across values (docs/variants.md → Locking). Overlays in play:");
        for (String line : lines) b.append("\n  ").append(line);
        return b.toString();
    }

    private static void collectOverlayLines(JkBuild build, String module, java.util.List<String> lines) {
        for (String line : Variants.describeDependencyOverlays(build)) {
            lines.add(module == null ? line : module + ": " + line);
        }
    }
}
