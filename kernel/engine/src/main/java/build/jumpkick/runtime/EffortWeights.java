// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.cache.Cas;
import build.jumpkick.config.JkBuildParser;
import build.jumpkick.layout.BuildLayout;
import build.jumpkick.lock.Lockfile;
import build.jumpkick.lock.LockfileReader;
import build.jumpkick.model.JkBuild;
import build.jumpkick.task.FreshnessStamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Predicts each build step's progress-bar weight from the work it will actually do <em>this</em>
 * run, so skipped/cached steps shrink to a near-no-op slice and real work dominates the bar. Runs
 * once at pipeline-start using only on-disk state (freshness stamps, the lockfile, the CAS); a
 * misprediction just mis-sizes a slice and is closed by step-end auto-fill.
 *
 * <p>Pragmatic v1: skip detection is a cheap stamp+mtime probe ({@link FreshnessStamp#looksFresh}),
 * and a CAS-restore is folded into the full-compile weight (exact restore detection needs the
 * content-hashed action key, which depends on inputs earlier steps produce). Compile→test→package
 * are <em>correlated</em>: if compile is predicted to run, the steps consuming its output are
 * predicted to run too — so the common "edited a source" case sizes correctly rather than trusting
 * a now-stale stamp.
 *
 * <p>Weights are time-proportional shares (the bar normalises Σ weights to 100%). A skipped step
 * is {@value #SKIP} — a true no-op so cached/up-to-date work never occupies the bar. A real compile
 * is {@code ceil(sources × 0.1)}; a fetch is {@value #ARTIFACT_FETCH} per artifact; a jar package
 * is {@value #PACKAGE_JAR}. A test run is a fixed {@value #TEST_STARTUP} JVM-startup floor (forking
 * the test JVM + framework init dominates a short suite, and {@code run-tests} is serialized across
 * the workspace) plus {@value #TEST_METHOD} per discovered test method. (JDK download, shadow jar,
 * native image and OCI weights are tracked in their own command pipelines.)
 */
public final class EffortWeights {

    private EffortWeights() {}

    static final int SKIP = 0; // a step that does no work this run — off the bar entirely
    static final int RESTORE = 3; // compile output hard-linked back from the CAS (set live by the step)
    static final int TEST_STARTUP = 15; // COLD static guess for test JVM fork + framework init (see runTestsWeight)
    static final int TEST_METHOD = 8; // per @Test (and ParameterizedTest/&c.)
    // The *learnable* fixed floor for run-tests, decoupled from the larger cold TEST_STARTUP guess.
    // TEST_STARTUP (15 ≈ 2.25s at 150) was tuned to a slow reference machine and is used as floor()
    // in both the learned reconstruction and observedPerUnit's residual — so on a fast host, where a
    // whole small suite forks + runs in well under 2.25s, EVERY run-tests sample landed under the
    // floor and was dropped (residual ≤ 0), so run-tests could NEVER be learned and stayed pinned to
    // the 15-unit static guess forever (~10× hot, as the run-tests step reweight comment notes).
    // Keep 15 as the COLD reservation (runTestsWeight), but let the learnable floor be small so real
    // durations are captured and the learned rate can settle below the old floor. Measured test-JVM
    // startup on a fast host ≈ 200 ms ≈ 1.4 units; 2 is a safe minimum reservation just above it.
    static final int TEST_STARTUP_FLOOR = 2; // learnable fixed component of a run-tests step
    static final int COMPILE_FLOOR = 2; // javac/kotlinc fixed startup (the learned per-source rate adds to it)
    static final int ARTIFACT_FETCH = 8; // per dependency artifact downloaded

    /**
     * A weight unit is ≈150 ms — the {@code Pipeline} interpolation constant; the bridge between learned
     * ms and bar weight.
     */
    public static final int MS_PER_WEIGHT = 150;

    static final int PACKAGE_JAR = 5;
    static final int JDK_DOWNLOAD = 70; // a pinned JDK not yet on disk
    static final int SHADOW_RUN = 10; // fat/shadow jar
    static final int NATIVE_RUN = 100; // native-image build ≈ 10 steps × 10
    static final int OCI_RUN = 40; // OCI image build
    static final int OCI_SKIP = 2; // OCI image up-to-date

    /**
     * Per-step predicted weights for one module's build. {@code fullyCached} is set when every work
     * step (sync/compile/test/package) collapsed to {@link #SKIP} — the module is a no-op this run,
     * so its always-run steps (parse, resources, stamps, assemble) should also shrink to a token
     * "touch" instead of carrying their full static weight, which would otherwise reserve ~1.5 s of
     * phantom time per cached module in the workspace estimate for ~30 ms of real work.
     */
    public record Plan(
            int sync,
            int compileJava,
            int compileKotlin,
            int compileTest,
            int runTests,
            int pkg,
            boolean fullyCached) {}

    /** {@code ceil(sources × 0.1)}, floored at 1 once the step runs at all. */
    static int compileWeight(int sources) {
        return Math.max(1, (sources + 9) / 10);
    }

    /**
     * Weight of an actually-running test step: a fixed JVM-startup floor plus a per-method term. The
     * floor matters because {@code run-tests} forks a JVM and is serialized across the workspace, so
     * even a small suite is a real, sequential chunk of wall-clock — without it the bar races through
     * the fast, parallel compile work and then stalls on the slow serial test tail. Shared by {@link
     * #predict} (up-front reservation) and the {@code run-tests} step's runtime reweight so they
     * agree.
     */
    public static int runTestsWeight(int methods) {
        return TEST_STARTUP + Math.max(0, methods) * TEST_METHOD;
    }

    /**
     * Fixed per-step startup floor (weight units) that the learned per-unit rate adds onto, and that
     * {@link #observedPerUnit} subtracts before learning. Deliberately SMALL for run-tests (the
     * learnable {@link #TEST_STARTUP_FLOOR}, not the larger cold {@link #TEST_STARTUP} guess): a big
     * floor here made every fast suite land under it and be dropped, so run-tests never learned.
     */
    static int floor(String step) {
        return switch (step) {
            case "run-tests" -> TEST_STARTUP_FLOOR;
            case "compile-java", "compile-kotlin", "compile-test" -> COMPILE_FLOOR;
            default -> 0;
        };
    }

    /** Minimum successful runs before a metrics average outranks a static constant. */
    static final int MIN_METRICS_SAMPLES = 3;

    /**
     * The metrics-learned flat weight for a fixed-cost step (package-jar, package-shadow,
     * native-image, write-image, plugin commands — steps {@link StepTimings} never learns a rate
     * for), tiered like {@link #learned}: this module's own running average when it has enough
     * successful samples, else the host-wide average, else the static constant — so an empty store
     * reproduces today's numbers exactly. Success-only ({@code ok} stats): a failed run's duration
     * teaches nothing.
     */
    static int learnedFixedWeight(String dir, String step, int staticWeight) {
        return learnedFixedWeight(BuildMetrics.load(BuildMetrics.defaultFile()), dir, step, staticWeight);
    }

    static int learnedFixedWeight(BuildMetrics metrics, String dir, String step, int staticWeight) {
        var own = metrics.step(dir, step);
        if (own.isPresent() && own.get().ok().count() >= MIN_METRICS_SAMPLES) {
            return flatWeight(own.get().ok().avgMillis());
        }
        var host = metrics.step("", step);
        if (host.isPresent() && host.get().ok().count() >= MIN_METRICS_SAMPLES) {
            return flatWeight(host.get().ok().avgMillis());
        }
        return staticWeight;
    }

    /** A whole-step historical average (ms) as a flat bar weight. */
    private static int flatWeight(long avgMillis) {
        return Math.max(1, (int) Math.round(avgMillis / (double) MS_PER_WEIGHT));
    }

    /** Back-compat overload with no project context — a two-tier fallback (module → host-median). */
    static int learned(StepTimings timings, String dir, String step, int count, int staticWeight) {
        return learned(timings, dir, step, count, staticWeight, java.util.List.of());
    }

    /**
     * The learned weight for a running step: {@code floor + rate × count}, where the per-unit {@code
     * rate} is, in order of preference (nearest locality first):
     *
     * <ol>
     *   <li><b>this exact (module, step)</b> if seen before — the module's own measured history;
     *   <li><b>the project/workspace median</b> for the step across {@code projectDirs} (the other
     *       modules of this build) — a closer prior than the whole host for a not-yet-built module,
     *       since siblings share frameworks/fixtures/setup cost;
     *   <li><b>the host median</b> for the step across <em>any</em> module this machine has built;
     *   <li><b>the running-metrics average</b> ({@code BuildMetrics}, a <em>flat</em> whole-step
     *       weight, not a rate) — the ledger lives in the cache and is wiped by {@code jk clean},
     *       while the metrics store is state and survives it, so this tier keeps the estimate
     *       host-real right after a clean instead of reverting to the static guess;
     *   <li><b>the static Step-1 estimate</b> ({@code staticWeight}) when nothing for the step has
     *       ever been recorded — so a fully cold machine reproduces Step 1 exactly.
     * </ol>
     *
     * <p>{@code projectDirs} empty (the back-compat overload) collapses tiers 2+3 to just the host
     * median, the prior behavior. Warm runs rescale whichever rate wins by the current {@code count}.
     */
    static int learned(
            StepTimings timings,
            String dir,
            String step,
            int count,
            int staticWeight,
            java.util.Collection<String> projectDirs) {
        return learned(timings, BuildMetrics.load(BuildMetrics.defaultFile()), dir, step, count, staticWeight, projectDirs);
    }

    static int learned(
            StepTimings timings,
            BuildMetrics metrics,
            String dir,
            String step,
            int count,
            int staticWeight,
            java.util.Collection<String> projectDirs) {
        double rate;
        var own = timings.perUnit(dir, step);
        if (own.isPresent()) {
            rate = own.getAsDouble();
        } else {
            var project = timings.medianPerUnit(step, projectDirs);
            if (project.isPresent()) {
                rate = project.getAsDouble();
            } else {
                var host = timings.medianPerUnit(step);
                if (host.isEmpty()) {
                    // No rate anywhere (cold ledger, e.g. right after `jk clean`) — fall back to the
                    // surviving metrics history before conceding to the Step-1 static guess.
                    return learnedFixedWeight(metrics, dir, step, staticWeight);
                }
                rate = host.getAsDouble();
            }
        }
        return Math.max(1, (int) Math.round(floor(step) + rate * Math.max(0, count)));
    }

    /**
     * The per-unit weight one step run teaches the ledger: its measured duration (converted to
     * weight units) minus the fixed {@link #floor}, spread over the unit count. Returns a negative
     * sentinel when the step plainly did no per-unit work (cache hit / skip: it finished at or under
     * its floor) so the recorder can drop it instead of teaching a ~0 rate.
     */
    public static double observedPerUnit(String step, long durationMs, int count) {
        double actualWeight = durationMs / (double) MS_PER_WEIGHT;
        double residual = actualWeight - floor(step);
        if (residual <= 0) return -1;
        return residual / Math.max(1, count);
    }

    /** Predict the weights for {@code in}; never throws (degrades to skip-ish). */
    public static Plan predict(BuildPipelines.Inputs in, Cas cas, boolean compact, boolean useJava, boolean useKotlin) {
        return predict(in, cas, compact, useJava, useKotlin, false);
    }

    /**
     * As {@link #predict(BuildPipelines.Inputs, Cas, boolean, boolean, boolean)} but with a {@code
     * forceRebuild} hint: when set, the module is treated as definitely rebuilding
     * (compile/test/package reserved at running weights), even if its own on-disk stamps look fresh.
     * The workspace pre-scan sets this from {@code BuildPlanForecast}'s dependency-ordered dirty set,
     * so a module that will rebuild only because an upstream sibling changed reserves its real slice
     * up front — the bar's true total is known from the start instead of growing mid-build (which
     * slid the bar backward). A misprediction here can only over-reserve: the step's own runtime
     * cache check reweights it back down (the bar jumps forward), never up.
     */
    public static Plan predict(
            BuildPipelines.Inputs in,
            Cas cas,
            boolean compact,
            boolean useJava,
            boolean useKotlin,
            boolean forceRebuild) {
        boolean rerun = in.session().config().rebuildOr(false) || forceRebuild;
        // If jk.toml is newer than jk.lock AND the lock no longer satisfies all declared
        // deps, treat the module as dirty so parse-lock runs and updates the lock.
        boolean lockStale = !rerun && AutoLock.needsRelocking(in.dir(), in.lockFile());
        if (lockStale) rerun = true;

        int sync = predictSync(in, cas);
        // Learned per-unit rates (cold ⇒ empty ⇒ static Step-1 weights). Keyed by
        // module dir, the same key the recorder writes at build end.
        StepTimings timings = StepTimings.load(in.cache());
        String mod = in.dir().toString();
        // Sibling modules of this build, for the project-tier learned fallback (empty ⇒ host-median).
        List<String> projectDirs =
                in.projectModules().stream().map(Path::toString).toList();
        int compileJava = SKIP, compileKotlin = SKIP, compileTest = SKIP, runTests = SKIP, pkg = SKIP;
        try {
            JkBuild project = JkBuildParser.parse(in.buildFile());
            BuildLayout layout = BuildLayout.of(in.dir(), project);

            boolean javaRun = false;
            if (useJava) {
                List<Path> src = CompileSupport.collectJavaSources(
                        compact ? in.dir().resolve("src") : in.dir().resolve("src/main/java"));
                javaRun = rerun || !FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.JAVA_STAMP, src);
                compileJava = javaRun
                        ? learned(timings, mod, "compile-java", src.size(), compileWeight(src.size()), projectDirs)
                        : SKIP;
            }
            boolean ktRun = false;
            if (useKotlin) {
                List<Path> src = CompileSupport.collectKotlinSources(in.dir(), compact);
                ktRun = rerun
                        || !FreshnessStamp.looksFresh(layout.kotlinClassesDir(), FreshnessStamp.KOTLIN_STAMP, src);
                compileKotlin = ktRun
                        ? learned(timings, mod, "compile-kotlin", src.size(), compileWeight(src.size()), projectDirs)
                        : SKIP;
            }
            boolean compileRun = javaRun || ktRun;

            // Tests + packaging consume the compiled output: if a compile ran (or
            // --force), they run. The precise test skip is decided at run-tests via
            // the CAS marker (which survives `jk clean`); that step reweights down
            // to SKIP there, so a fully-cached run lands right with no up-front guess.
            List<Path> testSrc = new ArrayList<>();
            testSrc.addAll(CompileSupport.collectJavaSources(
                    compact ? in.dir().resolve("test") : in.dir().resolve("src/test/java")));
            testSrc.addAll(CompileSupport.collectKotlinTestSources(in.dir(), compact));
            boolean testWillRun = !testSrc.isEmpty() && (rerun || compileRun);
            // compile-test is an opaque, batch javac/kotlinc call: the step declares
            // .ticks(1), so the recorder learns its rate against a count of 1 — i.e. the
            // learned value IS the whole-step weight, not a per-file rate. Forecast it
            // with count=1 to match (a flat per-step cost). Multiplying it by the test
            // file count here was the bug that ballooned the estimate (e.g. ×46 → ~40s
            // of pure fiction for a ~1.2s compile). The static cold fallback stays a
            // file-count guess. (compile-java is consistent: its .ticks is the source
            // count, the same count predict multiplies, so it stays per-source.)
            compileTest = testWillRun
                    ? learned(timings, mod, "compile-test", 1, compileWeight(testSrc.size()), projectDirs)
                    : SKIP;

            int methods = in.estimatedTestCount();
            runTests = testWillRun
                    ? learned(timings, mod, "run-tests", methods, runTestsWeight(methods), projectDirs)
                    : SKIP;

            boolean jarFresh = !rerun && !compileRun && Files.isRegularFile(layout.mainJar());
            pkg = jarFresh ? SKIP : learnedFixedWeight(mod, "package-jar", PACKAGE_JAR);
        } catch (Exception ignored) {
            // Unparseable project / layout — parse-build will surface the real
            // error; skip-ish weights + auto-fill keep the bar honest meanwhile.
        }
        // A module whose every work step skipped is fully cached: its always-run
        // steps will do trivial work (re-parse the build file, re-check stamps), so
        // the pipeline shrinks them to a token touch rather than full static weight.
        boolean fullyCached = sync == SKIP
                && compileJava == SKIP
                && compileKotlin == SKIP
                && compileTest == SKIP
                && runTests == SKIP
                && pkg == SKIP;
        return new Plan(sync, compileJava, compileKotlin, compileTest, runTests, pkg, fullyCached);
    }

    /**
     * {@code ensure-jdk}: 70 only when a JDK download will actually happen — the same condition
     * {@link JdkEnsure} uses ({@code resolve} finds no usable JDK across the whole order, including
     * the current/PATH tiers, and a spec <em>would install</em>). {@code resolve} is offline; the
     * download it predicts is the network cost. Anything resolvable on disk → 1.
     */
    public static int jdkWeight(Path dir, Path jdksDir) {
        try {
            JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
            Lockfile lock = Files.exists(dir.resolve("jk.lock")) ? LockfileReader.read(dir.resolve("jk.lock")) : null;
            build.jumpkick.jdk.JdkRegistry registry =
                    jdksDir != null ? new build.jumpkick.jdk.JdkRegistry(jdksDir) : new build.jumpkick.jdk.JdkRegistry();
            var req = new build.jumpkick.jdk.JdkResolution.Request(
                    dir,
                    build.jumpkick.config.SessionContext.current().jdkSpec(),
                    System.getenv("JK_JDK"),
                    lock != null ? lock.jdk() : null,
                    project.project() != null ? project.project().jdk() : null,
                    project.project() != null ? project.project().javaRelease() : 0,
                    System::getenv);
            var r = build.jumpkick.jdk.JdkResolution.resolve(
                    req,
                    registry,
                    build.jumpkick.jdk.GlobalDefaultJdk.current(),
                    build.jumpkick.jdk.JdkLts.OFFLINE_LATEST_LTS);
            return (r.jdk().isEmpty() && r.wouldInstall()) ? JDK_DOWNLOAD : SKIP;
        } catch (Exception e) {
            return SKIP;
        }
    }

    /**
     * Fat/shadow jar present and at least as new as the main jar (and not {@code --force}) → skip.
     */
    public static int shadowWeight(Path dir) {
        return artifactFresh(dir, BuildLayout::shadowJar)
                ? SKIP
                : learnedFixedWeight(dir.toString(), "package-shadow", SHADOW_RUN);
    }

    /** Native binary/library present and fresh → skip; otherwise a full native-image build. */
    public static int nativeWeight(Path dir) {
        return artifactFresh(dir, BuildLayout::nativeBinary) || artifactFresh(dir, BuildLayout::nativeLibrary)
                ? SKIP
                : learnedFixedWeight(dir.toString(), "native-image", NATIVE_RUN);
    }

    /** OCI image tarball present and fresh → skip (2); otherwise a full image build (40). */
    public static int ociWeight(Path dir) {
        return artifactFresh(dir, BuildLayout::ociImageTar)
                ? OCI_SKIP
                : learnedFixedWeight(dir.toString(), "write-image", OCI_RUN);
    }

    /**
     * True when the artifact selected by {@code artifact} exists, isn't being forced by {@code
     * --force}, and is at least as new as the main jar it's derived from — a cheap "this output is
     * up-to-date" proxy for the artifact-cache skip the step itself performs.
     */
    private static boolean artifactFresh(Path dir, java.util.function.Function<BuildLayout, Path> artifact) {
        try {
            if (build.jumpkick.config.SessionContext.current().config().rebuildOr(false)) return false;
            JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
            BuildLayout layout = BuildLayout.of(dir, project);
            Path art = artifact.apply(layout);
            if (!Files.isRegularFile(art)) return false;
            Path mainJar = layout.mainJar();
            if (!Files.isRegularFile(mainJar)) return true; // nothing to compare against
            return Files.getLastModifiedTime(art).toMillis()
                    >= Files.getLastModifiedTime(mainJar).toMillis();
        } catch (Exception e) {
            return false;
        }
    }

    /** Fetch weight: 8 per artifact not already in the CAS (all of them under {@code  --force}). */
    private static int predictSync(BuildPipelines.Inputs in, Cas cas) {
        try {
            if (!Files.exists(in.lockFile())) return ARTIFACT_FETCH; // first run resolves+fetches
            Lockfile lock = LockfileReader.read(in.lockFile());
            int fetches = 0;
            for (Lockfile.Artifact a : lock.artifacts()) {
                String checksum = a.checksum();
                if (checksum == null) continue; // pom-only / path / git — nothing to fetch
                // Only artifacts missing from the CAS cost anything to sync. --force/--refresh does
                // NOT re-download blobs already present: the CAS is content-addressed (a stored
                // sha256 is byte-identical), so a forced build resolves entirely from local disk —
                // it even succeeds offline. Reserving a per-artifact download here for cached deps
                // was the bug that made `jk explain --force` predict tens of seconds of phantom fetch.
                String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
                if (!cas.contains(hex)) fetches++;
            }
            return fetches == 0 ? SKIP : fetches * ARTIFACT_FETCH;
        } catch (Exception e) {
            return SKIP;
        }
    }

    // --- parallel-aware wall-clock estimate ----------------------------------

    /**
     * One module's cost for the schedule estimate: its weight, its serialized test weight, and its
     * prereqs.
     */
    public record ModuleCost(Path dir, Set<Path> prereqs, int weight, int testWeight) {}

    /**
     * The {@link ModuleCost} of a prepared module pipeline: its total estimated bar weight, plus the
     * serialized {@code run-tests} slice pulled out on its own (the schedule estimate treats that
     * step as a cross-module serial bound). Shared by {@code jk build} and {@code jk explain} so
     * their wall-clock estimates are computed from the pipeline identically.
     */
    public static ModuleCost costOf(Path dir, Set<Path> prereqs, build.jumpkick.run.Pipeline pipeline) {
        int weight = pipeline.estimatedTotalWeight();
        int testWeight = pipeline.steps().stream()
                .filter(p -> p.name().equals("run-tests"))
                .mapToInt(build.jumpkick.run.Step::estimateWeight)
                .sum();
        return new ModuleCost(dir, prereqs, weight, testWeight);
    }

    /**
     * Estimate a build's wall-clock (ms) from per-module weights, honoring how {@code jk build}
     * actually schedules. Serial ({@code --no-parallel}) sums every module's weight. The parallel
     * graph build overlaps independent modules, so the estimate is the largest of three lower bounds
     * — the dependency <b>critical path</b> (longest weighted chain, since a module can't start
     * before its prereqs finish), the <b>throughput</b> ceiling (Σweight ÷ concurrency, when work
     * saturates the worker JVMs), and — when tests are serialized across modules (the default, no
     * {@code --parallel-tests}) — the <b>serial test-step total</b>, since those steps share one
     * test JVM and cannot overlap. Summing everything (the old estimate) over-counts the
     * compile/package work that overlaps the long serial test tail.
     */
    public static long scheduleMillis(List<ModuleCost> mods, int concurrency, boolean serial, boolean parallelTests) {
        return scheduleMillis(mods, concurrency, serial, parallelTests, MS_PER_WEIGHT);
    }

    /**
     * As {@link #scheduleMillis(List, int, boolean, boolean)} but with an explicit weight→ms
     * conversion. Pass {@link #MS_PER_WEIGHT} on a warm machine (learned rates already encode this
     * host, so the constant round-trips exactly); pass a {@link Calibration}-measured or live
     * re-projected rate on a cold machine, where the constant is a blind guess. Applying a measured
     * rate only when the learned ledger is cold keeps it from stacking on top of learned rates.
     */
    public static long scheduleMillis(
            List<ModuleCost> mods, int concurrency, boolean serial, boolean parallelTests, long msPerWeight) {
        long serialSum = 0;
        long testSum = 0;
        for (ModuleCost m : mods) {
            serialSum += m.weight();
            testSum += m.testWeight();
        }
        if (serial || concurrency <= 1) return serialSum * msPerWeight;
        long critical = criticalPath(mods);
        long throughput = (serialSum + concurrency - 1) / concurrency;
        long testFloor = parallelTests ? 0 : testSum;
        return Math.max(critical, Math.max(throughput, testFloor)) * msPerWeight;
    }

    /**
     * As {@link #scheduleMillis(List, int, boolean, boolean, long)} but with a <em>per-module</em>
     * weight→ms rate. A single workspace-wide rate mis-priced the common mixed case — a brand-new
     * module beside already-built ones: the whole estimate had to pick one rail, so either the new
     * module was priced at {@link #MS_PER_WEIGHT} (the reference machine, ~4× hot on a fast host,
     * because its static reference-frame weights don't encode this host) or the built modules were
     * priced at the calibration rate (wrong for them — their learned rates already round-trip at 150).
     * Pricing each module by its own learned-ness fixes both at once: a warm module (learned rates for
     * its dir) converts at 150; a cold module converts at this host's measured calibration.
     *
     * <p>Implemented by pre-scaling each module's weights into ms-space with its own rate, then
     * running the identical schedule model at 1 ms/unit — so the critical-path / throughput /
     * serial-test bounds compose across modules that were priced differently.
     */
    public static long scheduleMillis(
            List<ModuleCost> mods,
            int concurrency,
            boolean serial,
            boolean parallelTests,
            java.util.function.ToDoubleFunction<Path> msPerWeightForModule) {
        List<ModuleCost> inMs = new ArrayList<>(mods.size());
        for (ModuleCost m : mods) {
            double r = msPerWeightForModule.applyAsDouble(m.dir());
            inMs.add(new ModuleCost(m.dir(), m.prereqs(), scaleToMs(m.weight(), r), scaleToMs(m.testWeight(), r)));
        }
        return scheduleMillis(inMs, concurrency, serial, parallelTests, 1L);
    }

    /** Weight × rate, rounded and clamped to a positive int (ms-space) so the schedule math can't overflow. */
    private static int scaleToMs(int weight, double rate) {
        long ms = Math.round(weight * Math.max(0.0, rate));
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, ms));
    }

    /**
     * Longest <em>blocking</em>-weighted path through the module DAG: {@code finish(m) = blocking(m)
     * + max prereq finish}. Only a module's blocking work (compile + package — what produces the jar
     * dependents wait on) sits on the path; its test step doesn't block dependents and is accounted
     * for separately by the serial test floor, so counting it here would double the tail.
     */
    private static long criticalPath(List<ModuleCost> mods) {
        java.util.Map<Path, ModuleCost> byDir = new java.util.HashMap<>();
        for (ModuleCost m : mods) byDir.put(m.dir(), m);
        java.util.Map<Path, Long> finish = new java.util.HashMap<>();
        long best = 0;
        for (ModuleCost m : mods) best = Math.max(best, pathFinish(m, byDir, finish));
        return best;
    }

    private static long pathFinish(
            ModuleCost m, java.util.Map<Path, ModuleCost> byDir, java.util.Map<Path, Long> memo) {
        Long cached = memo.get(m.dir());
        if (cached != null) return cached;
        long blocking = Math.max(0, m.weight() - m.testWeight());
        memo.put(m.dir(), blocking); // cycle guard
        long upstream = 0;
        for (Path p : m.prereqs()) {
            ModuleCost pm = byDir.get(p);
            if (pm != null) upstream = Math.max(upstream, pathFinish(pm, byDir, memo));
        }
        long f = blocking + upstream;
        memo.put(m.dir(), f);
        return f;
    }
}
