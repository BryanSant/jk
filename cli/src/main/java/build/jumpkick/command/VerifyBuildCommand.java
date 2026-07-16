// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.run.StepNames;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.cli.run.PipelineConsole;
import build.jumpkick.config.JkConfig;
import build.jumpkick.config.Session;
import build.jumpkick.config.SessionContext;
import build.jumpkick.engine.protocol.ProjectInfo;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineKey;
import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.Step;
import build.jumpkick.run.StepKind;
import build.jumpkick.runtime.ModulePlan;
import build.jumpkick.runtime.WorkspaceBuildListener;
import build.jumpkick.runtime.WorkspaceRequest;
import build.jumpkick.runtime.WorkspaceResult;
import build.jumpkick.util.Hashing;
import build.jumpkick.util.JkDirs;
import build.jumpkick.util.PathUtil;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk verify} — re-build the project into a clean scratch copy and compare the produced
 * artifacts' SHA-256 against the existing ones under {@code target/} (PRD §23.7).
 *
 * <p>The scratch rebuild is the <em>real</em> pipeline: the project tree (minus {@code target/}
 * outputs) is copied into a temp directory and rebuilt through the resident engine ({@link
 * build.jumpkick.cli.engine.EngineClient#buildWorkspace}) — the same host a normal {@code jk build}
 * uses — so Kotlin, annotation processing, declared tail steps, and the reproducible packaging
 * path are all exercised without the compile/packaging work ever entering the CLI's small heap.
 * The existing {@code jk.lock} is copied along (and kept fresh) so no re-resolve happens, and the
 * session is pinned to {@code rebuild} (which crosses the wire as its own request field) so no
 * action-cache restore can masquerade as a rebuild — and cache-bypassing runs skip the STORE
 * side too, so the scratch build (whose keys hash never-recurring temp paths) leaves no orphan
 * action-cache entries behind. From a workspace root every module's artifacts are verified.
 */
public final class VerifyBuildCommand implements CliCommand {

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public String description() {
        return "Rebuild from scratch and diff the artifacts vs target/";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.value(
                        "<dir>",
                        "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                        "--cache-dir")
                .hide());
    }

    private Path cacheDir;
    private GlobalOptions global;

    /** What parse-build decided to verify: the module dirs whose artifacts get compared. */
    private record VerifyPlan(List<Path> moduleDirs) {}

    /** One artifact's hash comparison; a {@code null} hash means the file does not exist. */
    private record Comparison(String artifact, String existingHash, String rebuiltHash) {
        boolean match() {
            return existingHash != null && existingHash.equals(rebuiltHash);
        }
    }

    /** All per-artifact comparisons, in module order. */
    private record Report(List<Comparison> comparisons) {}

    private static final PipelineKey<VerifyPlan> PLAN = PipelineKey.of("verify-plan", VerifyPlan.class);
    private static final PipelineKey<Path> SCRATCH = PipelineKey.of("scratch", Path.class);
    private static final PipelineKey<Report> REPORT = PipelineKey.of("report", Report.class);

    @Override
    public int run(Invocation in) throws IOException {
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile) || !Files.exists(lockFile)) {
            CliOutput.err(
                    "jk verify: jk.toml and jk.lock required in " + build.jumpkick.cli.PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        Step parseBuild = Step.builder(StepNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml + jk.lock");
                    ProjectInfo root = projectInfo(dir);
                    List<Path> moduleDirs = moduleDirs(dir, root);
                    // Every module the user built must have its jar in place before we rebuild —
                    // same "run `jk build` first" gate the single-jar verify always had. The
                    // workspace root itself is exempt (a pure aggregator produces no jar).
                    for (Path moduleDir : moduleDirs) {
                        if (root.workspaceRoot() && moduleDir.equals(dir)) continue;
                        Path jar = Path.of(projectInfo(moduleDir).mainJarPath());
                        if (!Files.exists(jar)) {
                            ctx.error("missing-jar", "no existing jar at " + jar + " — run `jk build` first.");
                            throw new RuntimeException("missing existing jar");
                        }
                    }
                    ctx.put(PLAN, new VerifyPlan(moduleDirs));
                    ctx.put(SCRATCH, Files.createTempDirectory("jk-verify-"));
                    ctx.progress(1);
                })
                .build();

        Step rebuild = Step.builder(StepNames.REBUILD_SCRATCH)
                .kind(StepKind.CPU)
                .requires(StepNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("rebuild into scratch");
                    Path scratch = ctx.require(SCRATCH);
                    try {
                        copyProjectTree(dir, scratch);
                        touchLockfiles(scratch);
                        buildScratch(scratch, cache, ctx::error);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        ctx.error("build", String.valueOf(e.getMessage()));
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Step compare = Step.builder(StepNames.COMPARE_HASHES)
                .requires(StepNames.REBUILD_SCRATCH)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("sha256 artifacts");
                    VerifyPlan plan = ctx.require(PLAN);
                    Path scratch = ctx.require(SCRATCH);
                    ctx.put(REPORT, new Report(compareArtifacts(dir, scratch, plan)));
                    ctx.progress(1);
                })
                .build();

        Pipeline pipeline = Pipeline.builder("verify-build")
                .addStep(parseBuild)
                .addStep(rebuild)
                .addStep(compare)
                .build();
        PipelineResult result = PipelineConsole.run(pipeline, PipelineConsole.modeFor(global), cache);
        pipeline.get(SCRATCH).ifPresent(PathUtil::deleteRecursively);

        if (!result.success()) {
            for (PipelineResult.Diagnostic d : result.errors()) {
                if ("missing-jar".equals(d.code())) return Exit.NO_INPUT;
            }
            return 1;
        }

        Report report = pipeline.get(REPORT).orElseThrow();
        long mismatches =
                report.comparisons().stream().filter(c -> !c.match()).count();
        if (!global.outputIsJson()) {
            for (Comparison c : report.comparisons()) {
                if (c.match()) {
                    CliOutput.out("ok        " + c.artifact() + "  " + c.existingHash());
                } else {
                    CliOutput.out("MISMATCH  " + c.artifact());
                    CliOutput.out("  existing: " + hashOrMissing(c.existingHash()));
                    CliOutput.out("  rebuilt : " + hashOrMissing(c.rebuiltHash()));
                }
            }
        }
        if (mismatches == 0) {
            if (!global.outputIsJson()) CliOutput.out("Reproducible.");
            return 0;
        }
        CliOutput.err("Not reproducible — " + mismatches + " artifact(s) differ.");
        return 1;
    }

    // ---- scratch rebuild --------------------------------------------------

    /** A sink for build-failure diagnostics — matches {@code StepContext.error}'s shape. */
    private interface ErrorSink {
        void error(String code, String message);
    }

    /**
     * Run the real build over the scratch copy through the resident engine (the scratch dir is the
     * request's {@code entryDir}), so the heavyweight compile/packaging work never runs in the
     * CLI's heap. The session is the current one with {@code rerun} pinned on — a genuine
     * recompile+repackage, never an action-cache restore of the very jar we are comparing against
     * — and {@code refresh} left alone, so locked dependencies still come from the local CAS (no
     * re-resolve, no network). Both cross the wire: the build request carries {@code rerun}
     * distinctly from {@code force} (see {@code EngineProtocol.buildRequest}). Tests are skipped:
     * verify compares artifacts, and the old verify never ran them either.
     *
     * <p>Under {@link #engineDisabledForTests()} the build runs in-process via the {@link
     * BuildService} facade instead — the test-suite-only escape hatch, mirroring {@code
     * BuildCommand}; production verify is always engine-hosted (no in-process fallback, per {@code
     * docs/engine.md}).
     */
    private static void buildScratch(Path scratch, Path cache, ErrorSink errors) throws Exception {
        // The parse feeds only the in-process test path; the hosted engine re-parses entryDir
        // itself (the build request serializes entryDir, not the model — thin client).
        JkBuild scratchBuild = engineDisabledForTests()
                ? build.jumpkick.cli.engine.InProcessEngine.require().parseBuild(scratch.resolve("jk.toml"))
                : null;
        var request = new WorkspaceRequest(
                scratch,
                scratchBuild,
                cache,
                null, // jdksDir: default install root
                1, // workers
                null, // profile
                true, // skipTests
                false, // verbose
                0, // module concurrency: auto
                null, // dirtyHint: rerun marks everything dirty anyway
                true, // only read by the in-process test path; the engine plans its own memory
                false); // verify must rebuild against the pinned lock verbatim — never freshen it
        Session session = SessionContext.current()
                .withConfig(SessionContext.current().config().mergedWith(withRerun()))
                .withWorkingDir(scratch)
                .withCacheDir(cache);
        List<String> buildErrors = Collections.synchronizedList(new ArrayList<>());
        WorkspaceBuildListener listener = new WorkspaceBuildListener() {
            @Override
            public PipelineListener onModuleStart(ModulePlan m) {
                return new PipelineListener() {
                    @Override
                    public void error(String step, String code, String message) {
                        buildErrors.add(step + ": " + message);
                    }
                };
            }
        };
        WorkspaceResult result = SessionContext.where(
                session,
                () -> engineDisabledForTests()
                        ? build.jumpkick.cli.engine.InProcessEngine.require().buildWorkspace(request, listener)
                        : build.jumpkick.cli.engine.EngineClient.buildWorkspace(
                                build.jumpkick.engine.EnginePaths.current(), request, listener));
        if (!result.errors().isEmpty()) {
            errors.error("build", String.join("; ", result.errors()));
            throw new RuntimeException("scratch rebuild failed");
        }
        if (!result.success()) {
            String detail = buildErrors.isEmpty() ? "see build diagnostics" : String.join("; ", buildErrors);
            errors.error("build", "scratch rebuild failed: " + detail);
            throw new RuntimeException("scratch rebuild failed");
        }
    }

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY: routes the scratch rebuild through the
     * in-process engine seam ({@code BuildService.buildWorkspace}) instead of the wire. Set via {@code
     * -Djk.test.noEngine=true} by {@code cli/build.gradle.kts}'s {@code test {}} task — never a
     * user-facing flag. Mirrors {@code BuildCommand}'s identical check; see the rationale there
     * (a Gradle test JVM has no real {@code jk} binary to exec as an engine).
     */
    private static boolean engineDisabledForTests() {
        // Also bypass inside a jk-forked test worker (jk.plugin.class=TestRunner): under the
        // self-hosted build, in-process dispatches would otherwise recurse into the very
        // engine hosting the test run and deadlock — see BuildCommand's javadoc.
        return Boolean.getBoolean("jk.test.noEngine")
                || "build.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }

    /** A config layer that sets only {@code rerun} — laid over the invocation's config. */
    private static JkConfig withRerun() {
        return new JkConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.of(true), // rebuild: bypass jk's caches, but never re-fetch (offline-safe)
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    // ---- module + artifact enumeration -------------------------------------

    /**
     * The directories whose artifacts get compared: the project itself for a plain project; for a
     * workspace root, the root (in case it carries its own sources) plus every declared module.
     */
    private static List<Path> moduleDirs(Path dir, ProjectInfo root) {
        if (!root.workspaceRoot()) return List.of(dir);
        List<Path> dirs = new ArrayList<>();
        dirs.add(dir);
        for (String module : root.moduleDirs()) {
            dirs.add(dir.resolve(module).normalize());
        }
        return dirs;
    }

    /** One module's jar-family artifact paths (main/shadow/sources/javadoc), engine-computed. */
    private static Path[] artifactPaths(Path moduleDir) throws IOException {
        ProjectInfo info = projectInfo(moduleDir);
        return new Path[] {
            Path.of(info.mainJarPath()),
            Path.of(info.shadowJarPath()),
            Path.of(info.sourcesJarPath()),
            Path.of(info.javadocJarPath()),
        };
    }

    /** The engine's project summary for {@code dir}; throws with the engine's message on error. */
    private static ProjectInfo projectInfo(Path dir) throws IOException {
        try {
            ProjectInfo info = engineDisabledForTests()
                    ? build.jumpkick.cli.engine.InProcessEngine.require().projectInfo(dir)
                    : build.jumpkick.cli.engine.EngineClient.projectInfo(build.jumpkick.engine.EnginePaths.current(), dir);
            if (info.error() != null) throw new IOException(info.error());
            return info;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Hash every jar-family artifact ({@code mainJar}/{@code shadowJar}/{@code sourcesJar}/{@code
     * javadocJar}) that exists on either side, per module. Native binaries and OCI tars are out of
     * scope — they are not byte-comparable across builds the way the jar path guarantees.
     */
    private static List<Comparison> compareArtifacts(Path dir, Path scratch, VerifyPlan plan) throws IOException {
        List<Comparison> comparisons = new ArrayList<>();
        for (Path moduleDir : plan.moduleDirs()) {
            Path[] existing = artifactPaths(moduleDir);
            Path scratchModule = scratch.resolve(dir.relativize(moduleDir));
            Path[] rebuilt = artifactPaths(scratchModule);
            comparisons.addAll(compareModule(dir, existing, rebuilt));
        }
        return comparisons;
    }

    /** Pair up one module's artifacts by kind; include a pair when either side produced the file. */
    private static List<Comparison> compareModule(Path displayRoot, Path[] existing, Path[] rebuilt)
            throws IOException {
        List<Comparison> out = new ArrayList<>();
        Path[][] pairs = {
            {existing[0], rebuilt[0]},
            {existing[1], rebuilt[1]},
            {existing[2], rebuilt[2]},
            {existing[3], rebuilt[3]},
        };
        for (Path[] pair : pairs) {
            String existingHash = hashIfPresent(pair[0]);
            String rebuiltHash = hashIfPresent(pair[1]);
            if (existingHash == null && rebuiltHash == null) continue;
            out.add(new Comparison(displayPath(displayRoot, pair[0]), existingHash, rebuiltHash));
        }
        return out;
    }

    private static String hashIfPresent(Path file) throws IOException {
        // Streamed (64 KiB buffer) — a large artifact never lands in the CLI's small heap.
        return Files.isRegularFile(file) ? Hashing.sha256Hex(file) : null;
    }

    private static String hashOrMissing(String hash) {
        return hash == null ? "(missing)" : hash;
    }

    private static String displayPath(Path root, Path artifact) {
        try {
            return root.relativize(artifact).toString().replace(java.io.File.separatorChar, '/');
        } catch (RuntimeException e) {
            return artifact.getFileName().toString();
        }
    }

    // ---- scratch checkout ---------------------------------------------------

    /**
     * Copy the project tree into the scratch root, excluding {@code .git} and every module's {@code
     * target/} output tree (a directory named {@code target} whose parent holds a {@code jk.toml}).
     * Attributes (mtimes) are preserved so the copied {@code jk.toml}↔{@code jk.lock} freshness
     * relationship survives; {@link #touchLockfiles} then bumps the locks regardless.
     */
    private static void copyProjectTree(Path srcRoot, Path destRoot) throws IOException {
        Files.walkFileTree(srcRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                if (!d.equals(srcRoot) && skip(d)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(destRoot.resolve(srcRoot.relativize(d)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                Files.copy(
                        f,
                        destRoot.resolve(srcRoot.relativize(f)),
                        StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** True for directories the scratch copy must not carry: VCS metadata and build outputs. */
    private static boolean skip(Path d) {
        String name = d.getFileName() == null ? "" : d.getFileName().toString();
        if (name.equals(".git")) return true;
        return name.equals("target")
                && d.getParent() != null
                && Files.exists(d.getParent().resolve("jk.toml"));
    }

    /**
     * Bump every copied {@code jk.lock} to now (later than any copied manifest's preserved mtime) so
     * the scratch build can never consider a lock stale and re-resolve — verify reuses the existing
     * lock verbatim.
     */
    private static void touchLockfiles(Path root) throws IOException {
        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        try (var stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.getFileName() != null && "jk.lock".equals(p.getFileName().toString())) {
                    Files.setLastModifiedTime(p, now);
                }
            }
        }
    }
}
