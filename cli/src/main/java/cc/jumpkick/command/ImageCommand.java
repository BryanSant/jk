// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk image} — build an OCI image for the project (PRD §22): the full build pipeline
 * (compile → test → package) plus the Jib-backed image step in the {@code jk-image-runner} worker,
 * so Jib, Guava, and the Google HTTP stack never load in the main jk process.
 *
 * <p><b>Engine-hosted</b> (Wave 2 of the slim-client migration): the pipeline and the worker fork
 * run inside the resident engine ({@link cc.jumpkick.cli.engine.EngineClient#runImage}); this
 * command renders the streamed pipeline events and themes the success tail from the structured
 * pipeline-finish fields. The pipeline machinery lives in {@link ImagePipelines} so the test-only in-process
 * path (see {@link #engineDisabledForTests}) builds the identical pipeline.
 */
public final class ImageCommand implements CliCommand {

    @Override
    public String name() {
        return "image";
    }

    @Override
    public String description() {
        return "Bundle this project into an OCI image";
    }

    @Override
    public List<Opt> options() {
        var opts = new java.util.ArrayList<Opt>(List.of(
                Opt.value("<class>", "Main class to set as the image entrypoint.", "--main"),
                Opt.value("<registry>", "Override image.registry from jk.toml.", "--registry"),
                Opt.value("<tag>", "Override image.tag from jk.toml.", "--tag"),
                Opt.value("<path>", "Write an OCI tarball instead of pushing.", "--tarball")
                        .withFallback(""),
                Opt.value("<exe>", "Docker/Podman executable (default: auto-detect).", "--docker-executable"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                Opt.flag("Skip compiling and running tests.", "--skip-tests")));
        opts.addAll(VariantSelection.options());
        return opts;
    }

    String mainClass;
    String registry;
    String tag;
    String tarballArg;
    String dockerExecutableArg;
    Path cacheDirOverride;
    Path jdksDir;
    cc.jumpkick.cli.BuildOptions buildOpts;
    GlobalOptions global;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; a real {@code jk image} invocation always
     * engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "cc.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.mainClass = in.value("main").orElse(null);
        this.registry = in.value("registry").orElse(null);
        this.tag = in.value("tag").orElse(null);
        this.tarballArg = in.value("tarball").orElse(null);
        this.dockerExecutableArg = in.value("docker-executable").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.buildOpts = new cc.jumpkick.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);
        Path projectDir = global.workingDir();
        VariantSelection.install(in, projectDir);
        Path jkBuildPath = projectDir.resolve("jk.toml");
        if (!Files.exists(jkBuildPath)) {
            CliOutput.err("jk image: " + jkBuildPath + " not found.");
            return Exit.NO_INPUT;
        }
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
        String module = BuildCommand.buildTarget(jkBuildPath, projectDir);

        PipelineResult result;
        cc.jumpkick.run.TestSummary testResult;
        if (engineDisabledForTests()) {
            var o = cc.jumpkick.cli.engine.InProcessEngine.require()
                    .imagePipeline(projectDir, cache, jdksDir, buildOpts.skipTests, global.verbose, mainClass,
                            registry, tag, tarballArg, dockerExecutableArg, mode, module);
            result = o.result();
            testResult = o.testResult();
        } else {
            // The wire has no real Pipeline, so the success tail renders from the structured fields the
            // terminal pipeline-finish carries — the summary holder is populated before the console
            // listener's own pipelineFinish fires, same holder pattern as TestCommand's hosted path.
            var session = cc.jumpkick.config.SessionContext.current();
            cc.jumpkick.cli.engine.EngineClient.ImageSummary[] summary =
                    new cc.jumpkick.cli.engine.EngineClient.ImageSummary[1];
            ConsoleSpec spec = new ConsoleSpec(
                    "Image",
                    r -> summary[0] != null
                            ? imageSuccessTail(
                                    summary[0].tarball(),
                                    summary[0].name(),
                                    summary[0].version(),
                                    summary[0].daemonExe(),
                                    summary[0].ref())
                            : "",
                    r -> "Image build failed",
                    true);
            try {
                result = cc.jumpkick.cli.engine.EngineClient.runImage(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineClient.ImageRequest(
                                projectDir,
                                cache,
                                jdksDir,
                                mainClass,
                                registry,
                                tag,
                                tarballArg,
                                dockerExecutableArg,
                                buildOpts.skipTests,
                                session.offline(),
                                session.force(),
                                session.config().rebuildOr(false),
                                global.verbose),
                        steps -> PipelineConsole.chooseConsoleListener(steps, mode, spec, module),
                        summary);
            } catch (IOException e) {
                CliOutput.err("jk image: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            testResult = summary[0] != null ? summary[0].testResult() : null;
        }

        if (!result.success()) {
            for (PipelineResult.Diagnostic d : result.errors()) {
                if ("no-main".equals(d.code())) return Exit.USAGE;
            }
            if (testResult != null && !testResult.allPassed()) return 4;
            return 1;
        }
        return 0;
    }

    /**
     * Success tail for the Image chip line, from the structured mode fields:
     *
     * <ul>
     *   <li>Tarball: {@code Wrote OCI tarball <path>}
     *   <li>Daemon load: {@code Loaded OCI image <name>:<version> into <docker|podman>}
     *   <li>Registry push: {@code Pushed <ref>}
     * </ul>
     *
     * The framework appends {@code took Xs} automatically. Theming happens here, client-side — the
     * engine only ever supplies the plain field values.
     */
    static String imageSuccessTail(String tarball, String name, String version, String daemonExe, String ref) {
        if (tarball != null) {
            return "Wrote OCI tarball " + Theme.colorize(tarball, Theme.active().path());
        }
        if (daemonExe != null) {
            return "Loaded OCI image "
                    + Theme.colorize(name != null ? name : "", Coords.artifactStyle())
                    + ":"
                    + Theme.colorize(version != null ? version : "", Coords.versionStyle())
                    + " into "
                    + daemonExe;
        }
        return "Pushed " + Theme.colorize(ref != null ? ref : "", Theme.active().path());
    }
}
