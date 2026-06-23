// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.JavacLint;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.runtime.BuildGraph;
import dev.jkbuild.runtime.BuildPipeline;
import dev.jkbuild.runtime.CompileSupport;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.task.ClasspathFingerprint;
import dev.jkbuild.task.FreshnessStamp;
import dev.jkbuild.task.JavaIncrementalCompile;
import dev.jkbuild.task.TestStamp;
import dev.jkbuild.util.JkVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Forecasts what {@code jk build} would do, phase by phase, for every module in
 * the {@link BuildGraph} — without executing anything. It reuses the build's own
 * cache primitives so the prediction matches reality:
 *
 * <ul>
 *   <li><b>compile-main / compile-test</b> — {@link JavaIncrementalCompile#predict}
 *       on the same {@link CompileRequest} the build assembles (exact action key →
 *       CACHE_HIT / FULL / INCREMENTAL).</li>
 *   <li><b>run-tests</b> — {@link TestStamp#computeKey} + a read-only
 *       {@link ActionCache#lookup}.</li>
 *   <li><b>package-jar</b> — {@link ActionKey#forArtifact} + a read-only lookup.</li>
 * </ul>
 *
 * <p>The one correctness rule that makes this a <em>faithful</em> forecast rather
 * than a per-phase snapshot: <b>dirty-propagation in dependency order</b>. A
 * downstream phase's cache key is derived from outputs an upstream phase is about
 * to change, so a current-disk lookup would falsely report "cached". Therefore, if
 * a module's compile does real work — or any of its workspace-sibling dependencies
 * is dirty (its classpath jar changes) — every consumer (compile-test → run-tests →
 * package) is reported as <em>will run</em>. Only a fully cache-clean chain trusts
 * the downstream keys.
 *
 * <p>Read-only lookups make any residual key drift <em>safe</em>: a miscomputed
 * downstream key misses the cache and is reported as "will run" (pessimistic) — it
 * can never produce a false "cached".
 */
final class BuildPlanForecast {

    private BuildPlanForecast() {}

    /** Per-phase verdict. CACHED = restored from cache; the rest do real work. */
    enum Status { CACHED, FULL, PARTIAL, RUN }

    /**
     * One phase of a module's build. {@code text} is the right-hand detail after
     * the status glyph; {@code key} is the 8-char action key when known (cached).
     */
    record Phase(String name, Status status, String text, String key) {
        boolean cached() { return status == Status.CACHED; }
    }

    /** A module's forecast: its build unit and the ordered phases that apply to it. */
    record Module(BuildGraph.BuildUnit unit, List<Phase> phases) {
        boolean dirty() { return phases.stream().anyMatch(p -> !p.cached()); }
    }

    /** Forecast every module in {@code graph}, in topological (dependency) order. */
    static List<Module> of(BuildGraph.Result graph, Cas cas, ActionCache actionCache, Path cache) {
        List<Module> out = new ArrayList<>();
        // Dirs whose *main output* will change this build — seeds downstream and
        // cross-module dirtiness. Filled as we walk in dependency order.
        Set<Path> dirty = new java.util.HashSet<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            boolean depDirty = false;
            for (Path dep : graph.edges().getOrDefault(u.dir(), Set.of())) {
                if (dirty.contains(dep)) { depDirty = true; break; }
            }
            Module m = forecastModule(u, depDirty, cas, actionCache, cache);
            // A module's consumed output changes — and so seeds downstream dirtiness —
            // when its compile does real work (classes change) OR its jar will be
            // (re)packaged, or a dependency already changed. Package matters on its own:
            // a consumer's run-tests/compile classpath hashes the *content* of sibling
            // JARs, so an upstream whose compile is cached but whose jar is stale
            // repackages to a new jar and silently invalidates the consumer — which a
            // per-module lookup against the current (stale) jar would miss, falsely
            // reporting "cached". The build then reruns those phases and the live bar,
            // having reserved nothing for them, backslides. Seeding on package too keeps
            // the forecast pessimistic (safe) for the consumer.
            if (m.phases().stream().anyMatch(p -> !p.cached() && (
                    p.name().startsWith("compile-main") || p.name().startsWith("compile-kotlin")
                            || p.name().startsWith("package-jar")))
                    || depDirty) {
                dirty.add(u.dir());
            }
            out.add(m);
        }
        return out;
    }

    private static Module forecastModule(BuildGraph.BuildUnit u, boolean depDirty,
                                         Cas cas, ActionCache actionCache, Path cache) {
        JkBuild project = u.manifest();
        Path dir = u.dir();
        List<Phase> phases = new ArrayList<>();
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.isRegularFile(lockFile)) {
            phases.add(new Phase("compile-main", Status.RUN, "not locked yet (run `jk build`)", null));
            return new Module(u, phases);
        }
        try {
            Lockfile lock = LockfileReader.read(lockFile);
            ClasspathResolver resolver = new ClasspathResolver(cas);
            boolean compact = CompileSupport.isSimpleLayout(project.project(), dir);
            BuildLayout layout = BuildLayout.of(dir, project);
            int release = project.project().javaRelease();
            List<String> javacArgs = JavacLint.effectiveArgs(project.build().lint(), List.of());
            List<Path> processorCp = resolver.classpathFor(lock, Set.of(Scope.PROCESSOR));

            boolean compileDirty = depDirty;

            // ---- compile-main (Java) ----
            Path mainSrcDir = compact ? dir.resolve("src") : dir.resolve("src/main/java");
            List<Path> mainSrc = CompileSupport.collectJavaSources(mainSrcDir);
            if (!mainSrc.isEmpty()) {
                WorkspaceClasspath.Result sib =
                        WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN));
                List<Path> cp = BuildPipeline.mainCompileClasspath(lock, resolver, sib);
                Path out = layout.classesDir();
                CompileRequest req = CompileRequest.builder()
                        .sources(mainSrc).classpath(cp).outputDir(out).release(release)
                        .extraOptions(javacArgs).processorPath(processorCp).build();
                String taskId = ActionKey.qualifiedTaskId("compile-main", out);
                Path stateDir = cache.resolve("actions").resolve("incremental-java").resolve(taskId);
                var pred = JavaIncrementalCompile.predict(taskId, req, JkVersion.VERSION, actionCache, stateDir);
                phases.add(compilePhase("compile-main", pred, depDirty));
                if (!phases.get(phases.size() - 1).cached()) compileDirty = true;
            }

            // ---- compile-kotlin (best-effort: freshness stamp; no content key yet) ----
            List<Path> ktSrc = CompileSupport.collectKotlinSources(dir, compact);
            if (!ktSrc.isEmpty()) {
                boolean fresh = !depDirty
                        && FreshnessStamp.looksFresh(layout.kotlinClassesDir(), FreshnessStamp.KOTLIN_STAMP, ktSrc);
                phases.add(fresh
                        ? new Phase("compile-kotlin", Status.CACHED, "", null)
                        : new Phase("compile-kotlin", Status.FULL, "full compile · " + count(ktSrc.size(), "source"), null));
                if (!fresh) compileDirty = true;
            }

            // ---- compile-test ----
            Path javaTestDir = compact ? dir.resolve("test") : dir.resolve("src/test/java");
            List<Path> javaTest = CompileSupport.collectJavaSources(javaTestDir);
            List<Path> ktTest = CompileSupport.collectKotlinTestSources(dir, compact);
            boolean haveTests = !javaTest.isEmpty() || !ktTest.isEmpty();
            boolean testDirty = false;
            if (haveTests) {
                int testSrcCount = javaTest.size() + ktTest.size();
                if (compileDirty) {
                    phases.add(new Phase("compile-test", Status.RUN,
                            "recompile · main changed", null));
                    testDirty = true;
                } else if (!javaTest.isEmpty()) {
                    List<Path> baseCp = new ArrayList<>();
                    baseCp.add(layout.classesDir());
                    baseCp.addAll(testCompileClasspath(dir, project, lock, resolver));
                    Path testOut = layout.testClassesDir();
                    // Mirror TestSupport.compileWithCache EXACTLY, including the
                    // processor path it now passes — the build runs declared
                    // annotation processors over test sources, so the action key
                    // hashes the same `pp:` lines. Omitting it here would compute a
                    // different key, miss the cache, and falsely forecast a rebuild.
                    CompileRequest req = CompileRequest.builder()
                            .sources(javaTest).classpath(baseCp).outputDir(testOut).release(release)
                            .extraOptions(javacArgs).processorPath(processorCp).build();
                    String taskId = ActionKey.qualifiedTaskId("compile-test", testOut);
                    Path stateDir = cache.resolve("actions").resolve("incremental-java").resolve(taskId);
                    var pred = JavaIncrementalCompile.predict(taskId, req, JkVersion.VERSION, actionCache, stateDir);
                    Phase p = compilePhase("compile-test", pred, false);
                    phases.add(p);
                    if (!p.cached()) testDirty = true;
                } else {
                    // Kotlin-only tests: no content predictor — assume fresh when main is clean.
                    phases.add(new Phase("compile-test", Status.CACHED, "", null));
                }

                // ---- run-tests ----
                int estimated = TestCommand.estimateTestCount(javaTestDir);
                String tests = estimated > 0 ? "~" + count(estimated, "test") : "tests";
                if (compileDirty || testDirty) {
                    phases.add(new Phase("run-tests", Status.RUN, "run tests · " + tests, null));
                } else {
                    // Mirror the build's run-tests stamp EXACTLY: main classes are a
                    // separate computeKey arg, NOT part of the runtime classpath.
                    List<Path> testRt = testRuntimeClasspath(dir, project, lock, resolver);
                    List<Path> testSrc = new ArrayList<>(javaTest);
                    testSrc.addAll(ktTest);
                    String stampKey = TestStamp.computeKey(testSrc, layout.classesDir(), lockFile, testRt,
                            BuildPipeline.testStampExtras(dir, project));
                    boolean hit = stampKey != null && present(actionCache, stampKey);
                    phases.add(hit
                            ? new Phase("run-tests", Status.CACHED, "· " + tests, null)
                            : new Phase("run-tests", Status.RUN, "run tests · " + tests, null));
                }
            }

            // ---- package-jar ----
            if (mainSrc.isEmpty() && ktSrc.isEmpty()) {
                // Source-less aggregator module — nothing to package.
            } else if (compileDirty) {
                phases.add(new Phase("package-jar", Status.RUN, "repackage · compile changed", null));
            } else {
                Path jar = layout.mainJar();
                String mainClass = project.project().main();
                List<String> tokens = List.of(
                        "classes:" + ClasspathFingerprint.entry(layout.classesDir()),
                        "main:" + (mainClass == null ? "" : mainClass),
                        "manifest:" + project.manifest());
                String pkgKey = ActionKey.forArtifact(
                        ActionKey.qualifiedTaskId("package-jar", jar), JkVersion.VERSION, tokens);
                boolean hit = present(actionCache, pkgKey);
                phases.add(hit
                        ? new Phase("package-jar", Status.CACHED, "", key8(pkgKey))
                        : new Phase("package-jar", Status.RUN, "repackage", null));
            }

            // ---- package-shadow (fat jar) — only when configured ----
            if (project.project().shadow() && !(mainSrc.isEmpty() && ktSrc.isEmpty())) {
                boolean fresh = !compileDirty && Files.isRegularFile(layout.shadowJar());
                phases.add(fresh
                        ? new Phase("package-shadow", Status.CACHED, "", null)
                        : new Phase("package-shadow", Status.RUN, "repackage", null));
            }
        } catch (Exception e) {
            // Degrade gracefully — never crash explain over one unparseable module.
            phases.add(new Phase("compile-main", Status.RUN,
                    "could not predict (" + e.getClass().getSimpleName() + ")", null));
        }
        return new Module(u, phases);
    }

    /** Map a {@link JavaIncrementalCompile.Prediction} to a phase, honoring upstream dirtiness. */
    private static Phase compilePhase(String name, JavaIncrementalCompile.Prediction pred, boolean depDirty) {
        return switch (pred.outcome()) {
            case CACHE_HIT -> depDirty
                    ? new Phase(name, Status.RUN, "recompile · dependency changed", null)
                    : new Phase(name, Status.CACHED, "", key8(pred.actionKey()));
            case INCREMENTAL -> new Phase(name, Status.PARTIAL,
                    "compile · " + count(pred.sourceCount(), "source"), null);
            case FULL -> new Phase(name, Status.FULL,
                    "full compile · " + count(pred.sourceCount(), "source"), null);
        };
    }

    // --- the build's test classpaths, mirrored (best-effort; misses fail safe) ---

    private static List<Path> testCompileClasspath(Path dir, JkBuild project, Lockfile lock,
                                                   ClasspathResolver resolver) throws java.io.IOException {
        WorkspaceClasspath.Result sib =
                WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST));
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST));
        cp.addAll(sib.jars());
        for (Path sl : sib.siblingLockfiles()) {
            try {
                Lockfile s = LockfileReader.read(sl);
                for (Path p : resolver.classpathFor(s, ClasspathResolver.COMPILE_MAIN))
                    if (!cp.contains(p)) cp.add(p);
            } catch (Exception ignored) { /* best-effort */ }
        }
        return cp;
    }

    private static List<Path> testRuntimeClasspath(Path dir, JkBuild project, Lockfile lock,
                                                   ClasspathResolver resolver) throws java.io.IOException {
        WorkspaceClasspath.Result sib =
                WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST));
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.TEST));
        cp.addAll(sib.jars());
        for (Path sl : sib.siblingLockfiles()) {
            try {
                Lockfile s = LockfileReader.read(sl);
                for (Path p : resolver.classpathFor(s, ClasspathResolver.RUNTIME))
                    if (!cp.contains(p)) cp.add(p);
            } catch (Exception ignored) { /* best-effort */ }
        }
        return cp;
    }

    private static boolean present(ActionCache ac, String key) {
        try {
            return ac.lookup(key).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private static String key8(String key) {
        return key != null && key.length() >= 8 ? key.substring(0, 8) : key;
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
