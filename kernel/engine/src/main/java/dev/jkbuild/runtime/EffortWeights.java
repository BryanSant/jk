// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.task.FreshnessStamp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Predicts each build phase's progress-bar weight from the work it will
 * actually do <em>this</em> run, so skipped/cached phases shrink to a near-no-op
 * slice and real work dominates the bar. Runs once at goal-start using only
 * on-disk state (freshness stamps, the lockfile, the CAS); a misprediction just
 * mis-sizes a slice and is closed by phase-end auto-fill.
 *
 * <p>Pragmatic v1: skip detection is a cheap stamp+mtime probe
 * ({@link FreshnessStamp#looksFresh}), and a CAS-restore is folded into the
 * full-compile weight (exact restore detection needs the content-hashed action
 * key, which depends on inputs earlier phases produce). Compile→test→package are
 * <em>correlated</em>: if compile is predicted to run, the phases consuming its
 * output are predicted to run too — so the common "edited a source" case sizes
 * correctly rather than trusting a now-stale stamp.
 *
 * <p>Weights are time-proportional shares (the bar normalises Σ weights to 100%).
 * A skipped phase is {@value #SKIP} — a true no-op so cached/up-to-date work never
 * occupies the bar. A real compile is {@code ceil(sources × 0.1)}; a fetch is
 * {@value #ARTIFACT_FETCH} per artifact; a jar package is {@value #PACKAGE_JAR}.
 * A test run is a fixed {@value #TEST_STARTUP} JVM-startup floor (forking the test
 * JVM + framework init dominates a short suite, and {@code run-tests} is serialized
 * across the workspace) plus {@value #TEST_METHOD} per discovered test method. (JDK
 * download, shadow jar, native image and OCI weights are tracked in their own
 * command goals.)
 */
public final class EffortWeights {

    private EffortWeights() {}

    static final int SKIP           = 0;   // a phase that does no work this run — off the bar entirely
    static final int RESTORE        = 3;   // compile output hard-linked back from the CAS (set live by the phase)
    static final int TEST_STARTUP   = 15;  // fork the test JVM + framework init (paid even by a tiny suite)
    static final int TEST_METHOD    = 8;   // per @Test (and ParameterizedTest/&c.)
    static final int COMPILE_FLOOR  = 2;   // javac/kotlinc fixed startup (the learned per-source rate adds to it)
    static final int ARTIFACT_FETCH = 8;   // per dependency artifact downloaded

    /** A weight unit is ≈150 ms — the {@code Goal} interpolation constant; the bridge between learned ms and bar weight. */
    public static final int MS_PER_WEIGHT = 150;
    static final int PACKAGE_JAR    = 5;
    static final int JDK_DOWNLOAD   = 70;  // a pinned JDK not yet on disk
    static final int SHADOW_RUN     = 10;  // fat/shadow jar
    static final int NATIVE_RUN     = 100; // native-image build ≈ 10 steps × 10
    static final int OCI_RUN        = 40;  // OCI image build
    static final int OCI_SKIP       = 2;   // OCI image up-to-date

    /** Per-phase predicted weights for one module's build. */
    public record Plan(int sync, int compileJava, int compileKotlin,
                       int compileTest, int runTests, int pkg) {}

    /** {@code ceil(sources × 0.1)}, floored at 1 once the phase runs at all. */
    static int compileWeight(int sources) {
        return Math.max(1, (sources + 9) / 10);
    }

    /**
     * Weight of an actually-running test phase: a fixed JVM-startup floor plus a
     * per-method term. The floor matters because {@code run-tests} forks a JVM and
     * is serialized across the workspace, so even a small suite is a real,
     * sequential chunk of wall-clock — without it the bar races through the fast,
     * parallel compile work and then stalls on the slow serial test tail. Shared by
     * {@link #predict} (up-front reservation) and the {@code run-tests} phase's
     * runtime reweight so they agree.
     */
    public static int runTestsWeight(int methods) {
        return TEST_STARTUP + Math.max(0, methods) * TEST_METHOD;
    }

    /** Fixed per-phase startup floor (weight units) that the learned per-unit rate adds onto. */
    static int floor(String phase) {
        return switch (phase) {
            case "run-tests" -> TEST_STARTUP;
            case "compile-java", "compile-kotlin", "compile-test" -> COMPILE_FLOOR;
            default -> 0;
        };
    }

    /**
     * The learned weight for a running phase: {@code floor + rate × count}, where the
     * per-unit {@code rate} is, in order of preference, (1) this exact (module, phase)
     * if seen before, (2) the median rate this machine has measured for the phase
     * across <em>any</em> module, so a never-built module borrows a realistic rate
     * instead of the static constant, or (3) the static Phase-1 estimate
     * ({@code staticWeight}) when nothing for the phase has ever been recorded — so a
     * fully cold ledger still reproduces Phase 1 exactly, and warm runs rescale by the
     * current {@code count}.
     */
    static int learned(PhaseTimings timings, String dir, String phase, int count, int staticWeight) {
        var perUnit = timings.perUnit(dir, phase);
        double rate;
        if (perUnit.isPresent()) {
            rate = perUnit.getAsDouble();
        } else {
            var crossModule = timings.medianPerUnit(phase);
            if (crossModule.isEmpty()) return staticWeight;   // never seen this phase → Phase-1 static
            rate = crossModule.getAsDouble();
        }
        return Math.max(1, (int) Math.round(floor(phase) + rate * Math.max(0, count)));
    }

    /**
     * The per-unit weight one phase run teaches the ledger: its measured duration
     * (converted to weight units) minus the fixed {@link #floor}, spread over the
     * unit count. Returns a negative sentinel when the phase plainly did no per-unit
     * work (cache hit / skip: it finished at or under its floor) so the recorder can
     * drop it instead of teaching a ~0 rate.
     */
    public static double observedPerUnit(String phase, long durationMs, int count) {
        double actualWeight = durationMs / (double) MS_PER_WEIGHT;
        double residual = actualWeight - floor(phase);
        if (residual <= 0) return -1;
        return residual / Math.max(1, count);
    }

    /** Predict the weights for {@code in}; never throws (degrades to skip-ish). */
    public static Plan predict(BuildPipeline.Inputs in, Cas cas,
                               boolean compact, boolean useJava, boolean useKotlin) {
        return predict(in, cas, compact, useJava, useKotlin, false);
    }

    /**
     * As {@link #predict(BuildPipeline.Inputs, Cas, boolean, boolean, boolean)} but
     * with a {@code forceRebuild} hint: when set, the module is treated as
     * definitely rebuilding (compile/test/package reserved at running weights),
     * even if its own on-disk stamps look fresh. The workspace pre-scan sets this
     * from {@code BuildPlanForecast}'s dependency-ordered dirty set, so a module
     * that will rebuild only because an upstream sibling changed reserves its real
     * slice up front — the bar's true total is known from the start instead of
     * growing mid-build (which slid the bar backward). A misprediction here can only
     * over-reserve: the phase's own runtime cache check reweights it back down (the
     * bar jumps forward), never up.
     */
    public static Plan predict(BuildPipeline.Inputs in, Cas cas, boolean compact,
                               boolean useJava, boolean useKotlin, boolean forceRebuild) {
        boolean rerun = ActiveConfig.get().rerunOr(false) || forceRebuild;
        boolean refresh = ActiveConfig.get().refreshOr(false);

        int sync = predictSync(in, cas, refresh);
        // Learned per-unit rates (cold ⇒ empty ⇒ static Phase-1 weights). Keyed by
        // module dir, the same key the recorder writes at build end.
        PhaseTimings timings = PhaseTimings.load(in.cache());
        String mod = in.dir().toString();
        int compileJava = SKIP, compileKotlin = SKIP, compileTest = SKIP, runTests = SKIP, pkg = SKIP;
        try {
            JkBuild project = JkBuildParser.parse(in.buildFile());
            BuildLayout layout = BuildLayout.of(in.dir(), project);

            boolean javaRun = false;
            if (useJava) {
                List<Path> src = CompileSupport.collectJavaSources(
                        compact ? in.dir().resolve("src") : in.dir().resolve("src/main/java"));
                javaRun = rerun || !FreshnessStamp.looksFresh(
                        layout.classesDir(), FreshnessStamp.JAVA_STAMP, src);
                compileJava = javaRun
                        ? learned(timings, mod, "compile-java", src.size(), compileWeight(src.size())) : SKIP;
            }
            boolean ktRun = false;
            if (useKotlin) {
                List<Path> src = CompileSupport.collectKotlinSources(in.dir(), compact);
                ktRun = rerun || !FreshnessStamp.looksFresh(
                        layout.kotlinClassesDir(), FreshnessStamp.KOTLIN_STAMP, src);
                compileKotlin = ktRun
                        ? learned(timings, mod, "compile-kotlin", src.size(), compileWeight(src.size())) : SKIP;
            }
            boolean compileRun = javaRun || ktRun;

            // Tests + packaging consume the compiled output: if a compile ran (or
            // --rerun), they run. The precise test skip is decided at run-tests via
            // the CAS marker (which survives `jk clean`); that phase reweights down
            // to SKIP there, so a fully-cached run lands right with no up-front guess.
            List<Path> testSrc = new ArrayList<>();
            testSrc.addAll(CompileSupport.collectJavaSources(
                    compact ? in.dir().resolve("test") : in.dir().resolve("src/test/java")));
            testSrc.addAll(CompileSupport.collectKotlinTestSources(in.dir(), compact));
            boolean testWillRun = !testSrc.isEmpty() && (rerun || compileRun);
            // compile-test is an opaque, batch javac/kotlinc call: the phase declares
            // .scope(1), so the recorder learns its rate against a count of 1 — i.e. the
            // learned value IS the whole-phase weight, not a per-file rate. Forecast it
            // with count=1 to match (a flat per-phase cost). Multiplying it by the test
            // file count here was the bug that ballooned the estimate (e.g. ×46 → ~40s
            // of pure fiction for a ~1.2s compile). The static cold fallback stays a
            // file-count guess. (compile-java is consistent: its .scope is the source
            // count, the same count predict multiplies, so it stays per-source.)
            compileTest = testWillRun
                    ? learned(timings, mod, "compile-test", 1, compileWeight(testSrc.size())) : SKIP;

            int methods = in.estimatedTestCount();
            runTests = testWillRun
                    ? learned(timings, mod, "run-tests", methods, runTestsWeight(methods)) : SKIP;

            boolean jarFresh = !rerun && !compileRun && Files.isRegularFile(layout.mainJar());
            pkg = jarFresh ? SKIP : PACKAGE_JAR;
        } catch (Exception ignored) {
            // Unparseable project / layout — parse-build will surface the real
            // error; skip-ish weights + auto-fill keep the bar honest meanwhile.
        }
        return new Plan(sync, compileJava, compileKotlin, compileTest, runTests, pkg);
    }

    /**
     * {@code ensure-jdk}: 70 only when a JDK download will actually happen — the
     * same condition {@link JdkEnsure} uses ({@code resolve} finds no usable JDK
     * across the whole order, including the current/PATH tiers, and a spec
     * <em>would install</em>). {@code resolve} is offline; the download it
     * predicts is the network cost. Anything resolvable on disk → 1.
     */
    public static int jdkWeight(Path dir, Path jdksDir) {
        try {
            JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
            Lockfile lock = Files.exists(dir.resolve("jk.lock"))
                    ? LockfileReader.read(dir.resolve("jk.lock")) : null;
            dev.jkbuild.jdk.JdkRegistry registry = jdksDir != null
                    ? new dev.jkbuild.jdk.JdkRegistry(jdksDir)
                    : new dev.jkbuild.jdk.JdkRegistry();
            var req = new dev.jkbuild.jdk.JdkResolution.Request(
                    dir, System.getProperty("jk.jdk"), System.getenv("JK_JDK"),
                    lock != null ? lock.jdk() : null,
                    project.project() != null ? project.project().jdk() : null,
                    project.project() != null ? project.project().javaRelease() : 0,
                    System::getenv);
            var r = dev.jkbuild.jdk.JdkResolution.resolve(req, registry,
                    dev.jkbuild.jdk.GlobalDefaultJdk.current(),
                    dev.jkbuild.jdk.JdkLts.OFFLINE_LATEST_LTS);
            return (r.jdk().isEmpty() && r.wouldInstall()) ? JDK_DOWNLOAD : SKIP;
        } catch (Exception e) {
            return SKIP;
        }
    }

    /** Fat/shadow jar present and at least as new as the main jar (and not {@code --rerun}) → skip. */
    public static int shadowWeight(Path dir) {
        return artifactFresh(dir, BuildLayout::shadowJar) ? SKIP : SHADOW_RUN;
    }

    /** Native binary/library present and fresh → skip; otherwise a full native-image build. */
    public static int nativeWeight(Path dir) {
        return artifactFresh(dir, BuildLayout::nativeBinary)
                || artifactFresh(dir, BuildLayout::nativeLibrary) ? SKIP : NATIVE_RUN;
    }

    /** OCI image tarball present and fresh → skip (2); otherwise a full image build (40). */
    public static int ociWeight(Path dir) {
        return artifactFresh(dir, BuildLayout::ociImageTar) ? OCI_SKIP : OCI_RUN;
    }

    /**
     * True when the artifact selected by {@code artifact} exists, isn't being
     * forced by {@code --rerun}, and is at least as new as the main jar it's
     * derived from — a cheap "this output is up-to-date" proxy for the
     * artifact-cache skip the phase itself performs.
     */
    private static boolean artifactFresh(Path dir, java.util.function.Function<BuildLayout, Path> artifact) {
        try {
            if (ActiveConfig.get().rerunOr(false)) return false;
            JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
            BuildLayout layout = BuildLayout.of(dir, project);
            Path art = artifact.apply(layout);
            if (!Files.isRegularFile(art)) return false;
            Path mainJar = layout.mainJar();
            if (!Files.isRegularFile(mainJar)) return true;   // nothing to compare against
            return Files.getLastModifiedTime(art).toMillis()
                    >= Files.getLastModifiedTime(mainJar).toMillis();
        } catch (Exception e) {
            return false;
        }
    }

    /** Fetch weight: 8 per artifact not already in the CAS (all of them under {@code --refresh}). */
    private static int predictSync(BuildPipeline.Inputs in, Cas cas, boolean refresh) {
        try {
            if (!Files.exists(in.lockFile())) return ARTIFACT_FETCH; // first run resolves+fetches
            Lockfile lock = LockfileReader.read(in.lockFile());
            int fetches = 0;
            for (Lockfile.Artifact a : lock.artifacts()) {
                String checksum = a.checksum();
                if (checksum == null) continue;   // pom-only / path / git — nothing to fetch
                if (refresh) { fetches++; continue; }
                String hex = checksum.startsWith("sha256:")
                        ? checksum.substring("sha256:".length()) : checksum;
                if (!cas.contains(hex)) fetches++;
            }
            return fetches == 0 ? SKIP : fetches * ARTIFACT_FETCH;
        } catch (Exception e) {
            return SKIP;
        }
    }

}
