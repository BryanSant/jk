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
import dev.jkbuild.task.TestStamp;

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
 * <p>Weights (absolute; the bar normalises Σ weights to 100%): a skipped phase
 * is {@value #SKIP}; a real compile is {@code ceil(sources × 0.1)}; a fetch is
 * {@value #ARTIFACT_FETCH} per artifact; a test run is {@value #TEST_METHOD} per
 * test method; a jar package is {@value #PACKAGE_JAR}. (JDK download, shadow
 * jar, native image and OCI weights are tracked in their own command goals.)
 */
public final class EffortWeights {

    private EffortWeights() {}

    static final int SKIP           = 1;
    static final int TEST_METHOD    = 8;   // per @Test (and ParameterizedTest/&c.)
    static final int ARTIFACT_FETCH = 8;   // per dependency artifact downloaded
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

    /** Predict the weights for {@code in}; never throws (degrades to skip-ish). */
    public static Plan predict(BuildPipeline.Inputs in, Cas cas,
                               boolean compact, boolean useJava, boolean useKotlin) {
        boolean rerun = ActiveConfig.get().rerunOr(false);
        boolean refresh = ActiveConfig.get().refreshOr(false);

        int sync = predictSync(in, cas, refresh);
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
                compileJava = javaRun ? compileWeight(src.size()) : SKIP;
            }
            boolean ktRun = false;
            if (useKotlin) {
                List<Path> src = CompileSupport.collectKotlinSources(in.dir(), compact);
                ktRun = rerun || !FreshnessStamp.looksFresh(
                        layout.kotlinClassesDir(), FreshnessStamp.KOTLIN_STAMP, src);
                compileKotlin = ktRun ? compileWeight(src.size()) : SKIP;
            }
            boolean compileRun = javaRun || ktRun;

            // Tests + packaging consume the compiled output: if compile ran, they
            // run. Otherwise fall back to their own freshness (test stamp, jar).
            List<Path> testSrc = new ArrayList<>();
            testSrc.addAll(CompileSupport.collectJavaSources(
                    compact ? in.dir().resolve("test") : in.dir().resolve("src/test/java")));
            testSrc.addAll(CompileSupport.collectKotlinTestSources(in.dir(), compact));
            Path testStamp = layout.testClassesDir().resolve(TestStamp.FILE);
            boolean testWillRun = !testSrc.isEmpty()
                    && (rerun || compileRun || !testStampFresh(testStamp, testSrc));
            compileTest = testWillRun ? compileWeight(testSrc.size()) : SKIP;

            int methods = in.estimatedTestCount();
            runTests = (methods > 0 && testWillRun) ? methods * TEST_METHOD : SKIP;

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

    /** Test stamp present and no test source touched since it was written. */
    private static boolean testStampFresh(Path testStamp, List<Path> testSrc) {
        try {
            if (!Files.isRegularFile(testStamp)) return false;
            long stampMs = Files.getLastModifiedTime(testStamp).toMillis();
            for (Path s : testSrc) {
                if (Files.getLastModifiedTime(s).toMillis() > stampMs) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
