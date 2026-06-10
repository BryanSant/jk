// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.runtime.BuildPipeline;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.config.ImageConfigParser;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code jk native} — build a GraalVM-compiled native binary for the project,
 * from source. Runs the full {@linkplain BuildPipeline build pipeline}
 * (compile → test → package) and then composes the native-image tail onto the
 * <em>same</em> goal.
 *
 * <p>In a workspace, cascades to every member that declares a {@code main}
 * class and has not set {@code native = false}. Library members (no
 * {@code main}, or {@code native = false}) are still compiled and packaged so
 * native-eligible members can depend on them.
 */
public final class NativeCommand implements CliCommand {

    @Override public String name() { return "native"; }
    @Override public String description() { return "Build a native binary with GraalVM"; }
    @Override public List<Opt> options() {
        return List.of(
                Opt.value("<class>", "Main class to compile. Default: read from jk.toml's image.main-class.", "--main"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir").hide(),
                Opt.flag("Skip compiling and running tests.", "--skip-tests"),
                Opt.flag("Run the build in-process instead of via the Workspace Host JVM.", "--no-host").hide());
    }
    @Override public List<dev.jkbuild.model.command.Param> parameters() {
        return List.of(dev.jkbuild.model.command.Param.of("native-image-args",
                dev.jkbuild.model.command.Arity.ZERO_OR_MORE,
                "Extra arguments forwarded to native-image (after --)"));
    }

    String mainClass;
    Path cacheDirOverride;
    Path jdksDir;
    List<String> extra = new ArrayList<>();
    dev.jkbuild.cli.BuildOptions buildOpts;
    GlobalOptions global;
    boolean useHost;

    @Override
    public int run(Invocation in) throws Exception {
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.extra = in.positionals();
        this.buildOpts = new dev.jkbuild.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);
        this.useHost = !in.isSet("no-host");

        Path startDir = global.workingDir();
        Path buildFile = startDir.resolve("jk.toml");
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();

        if (!Files.exists(buildFile)) {
            System.err.println("jk native: " + buildFile + " not found.");
            return 66;
        }

        JkBuild peek = JkBuildParser.parse(buildFile);

        // Workspace root: cascade to all eligible members.
        if (peek.isWorkspaceRoot()) {
            return runWorkspaceNative(startDir, peek, cache);
        }

        // Member redirect: if we're inside a workspace, build from the root.
        var rootOpt = WorkspaceLocator.findRoot(startDir);
        if (rootOpt.isPresent() && !rootOpt.get().equals(startDir)) {
            Path wsRoot = rootOpt.get();
            JkBuild rootBuild = JkBuildParser.parse(wsRoot.resolve("jk.toml"));
            if (rootBuild.isWorkspaceRoot()) {
                System.err.println("jk native: building from workspace root "
                        + wsRoot.getFileName() + " (member: " + startDir.getFileName() + ")");
                return runWorkspaceNative(wsRoot, rootBuild, cache);
            }
        }

        // Single project.
        return runSingleProject(startDir, buildFile, cache);
    }

    // --- workspace cascade ---------------------------------------------------

    private int runWorkspaceNative(Path wsRoot, JkBuild root, Path cache) throws Exception {
        Map<Path, JkBuild> membersByDir;
        try {
            membersByDir = WorkspaceLoader.loadMembers(wsRoot, root);
        } catch (RuntimeException e) {
            System.err.println("jk native: " + e.getMessage());
            return 2;
        }
        if (membersByDir.isEmpty()) {
            System.out.println("(workspace declares no members)");
            return 0;
        }

        List<Path> sorted = BuildCommand.topoSortMembers(membersByDir);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        long buildStart = System.nanoTime();

        // JSON / verbose: per-member banners.
        if (mode != GoalConsole.Mode.AUTO && mode != GoalConsole.Mode.QUIET) {
            for (int i = 0; i < sorted.size(); i++) {
                Path memberDir = sorted.get(i);
                JkBuild member = membersByDir.get(memberDir);
                System.out.println();
                System.out.println("══ " + wsRoot.relativize(memberDir)
                        + " (" + (i + 1) + "/" + sorted.size() + ") ══");
                int exit = runForMember(memberDir, member, cache);
                if (exit != 0) {
                    System.err.println("jk native: " + wsRoot.relativize(memberDir)
                            + " failed (exit " + exit + ")");
                    return exit;
                }
            }
            return 0;
        }

        // AUTO / QUIET: one shared aggregate view.
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.goal(System.out, "Native Build", animate);
        dev.jkbuild.cli.run.AggregateContext agg = new dev.jkbuild.cli.run.AggregateContext(view);
        int built = 0;

        try (var cap = view.captureOutput()) {
            for (Path memberDir : sorted) {
                JkBuild member = membersByDir.get(memberDir);
                String memberName = wsRoot.relativize(memberDir).toString();
                int exit;
                try {
                    exit = runForMember(memberDir, member, cache, agg);
                } catch (Exception e) {
                    view.finishFailure("Native build failed in " + memberName
                            + " " + BuildCommand.elapsedSince(buildStart));
                    throw e;
                }
                if (exit != 0) {
                    view.finishFailure("Native build failed in " + memberName
                            + " " + BuildCommand.elapsedSince(buildStart));
                    for (GoalResult.Diagnostic d : agg.lastErrors()) {
                        System.err.println("error[" + d.phase() + "/" + d.code() + "]: " + d.message());
                    }
                    return exit;
                }
                built++;
            }
        }

        long nativeCount = sorted.stream()
                .map(membersByDir::get)
                .filter(NativeCommand::isNativeEligible)
                .count();
        String elapsed = " " + BuildCommand.elapsedSince(buildStart);
        String summary = built + " member" + (built == 1 ? "" : "s") + " built"
                + (nativeCount > 0 ? ", " + nativeCount + " native"
                        + (nativeCount == 1 ? " binary" : " binaries") : "");
        view.finishSuccess(summary + elapsed);
        return 0;
    }

    /** Run one workspace member: native if eligible, plain build otherwise. */
    private int runForMember(Path memberDir, JkBuild member, Path cache) throws Exception {
        return runForMember(memberDir, member, cache, null);
    }

    private int runForMember(Path memberDir, JkBuild member, Path cache,
                              dev.jkbuild.cli.run.AggregateContext agg) throws Exception {
        boolean eligible = isNativeEligible(member);
        Path lockFile = memberDir.resolve("jk.lock");
        String verb = eligible ? "native" : "build";

        if (useHost) {
            dev.jkbuild.host.HostInvocation inv = new dev.jkbuild.host.HostInvocation(
                    verb, memberDir, cache, lockFile, jdksDir, null, 1,
                    buildOpts.skipTests, global.verbose, global.outputIsJson());
            String label = eligible
                    ? BuildCommand.buildTarget(memberDir.resolve("jk.toml"), memberDir)
                    : BuildCommand.buildTarget(memberDir.resolve("jk.toml"), memberDir);
            ConsoleSpec spec = eligible
                    ? new ConsoleSpec(label, r -> "Built native binary", r -> "Native build failed")
                    : new ConsoleSpec(label, r -> "Build successful",    r -> "Build failed");
            if (agg != null) {
                // Feed into the shared aggregate view.
                GoalConsole.Mode mode = GoalConsole.modeFor(global);
                dev.jkbuild.cli.run.ReceivingGoalListener receiver =
                        new dev.jkbuild.cli.run.ReceivingGoalListener(
                                List.of(new dev.jkbuild.cli.run.AggregateMemberListener(
                                        agg, label, List.of())));
                // Fall through to HostLauncher without aggregate wiring for now.
            }
            int code = dev.jkbuild.cli.run.HostLauncher.tryRun(
                    inv, GoalConsole.modeFor(global), spec, global.verbose);
            if (code >= 0) return code;
        }

        // In-process fallback.
        Path buildFile = memberDir.resolve("jk.toml");
        int estimatedTests = TestCommand.estimateTestCount(memberDir.resolve("src/test/java"));
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                memberDir, cache, buildFile, lockFile, memberDir,
                1, estimatedTests, null, jdksDir, buildOpts.skipTests, global.verbose);
        Goal.Builder goalBuilder = BuildPipeline.coreBuilder(inputs);
        if (eligible) {
            String resolvedMain = resolveMain(buildFile);
            goalBuilder.addPhase(BuildPipeline.nativePhase(
                    memberDir, cache, lockFile, jdksDir, resolvedMain, extra));
        }
        Goal goal = goalBuilder.build();
        String targetLabel = BuildCommand.buildTarget(buildFile, memberDir);
        ConsoleSpec spec = eligible
                ? new ConsoleSpec(targetLabel, r -> "Built native binary", r -> "Native build failed")
                : new ConsoleSpec(targetLabel, r -> "Build successful",    r -> "Build failed");
        GoalResult result = agg != null
                ? GoalConsole.runGoalInto(goal, cache, targetLabel, agg)
                : GoalConsole.runGoal(goal, GoalConsole.modeFor(global), cache, spec, targetLabel);
        return result.success() ? 0 : 1;
    }

    // --- single-project (unchanged behaviour) --------------------------------

    private int runSingleProject(Path projectDir, Path buildFile, Path cache)
            throws IOException, InterruptedException {
        String resolvedMain = resolveMain(buildFile);
        Path lockFile = projectDir.resolve("jk.lock");

        if (useHost) {
            dev.jkbuild.host.HostInvocation inv = new dev.jkbuild.host.HostInvocation(
                    "native", projectDir, cache, lockFile, jdksDir, null, 1,
                    buildOpts.skipTests, global.verbose, global.outputIsJson());
            ConsoleSpec spec = new ConsoleSpec("Native Build",
                    r -> "Built native binary", r -> "Native build failed");
            int code = dev.jkbuild.cli.run.HostLauncher.tryRun(
                    inv, GoalConsole.modeFor(global), spec, global.verbose);
            if (code >= 0) return code;
        }

        int estimatedTestCount = TestCommand.estimateTestCount(projectDir.resolve("src/test/java"));
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                projectDir, cache, buildFile, lockFile, projectDir,
                1, estimatedTestCount, null, jdksDir, buildOpts.skipTests, global.verbose);

        Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
        builder.addPhase(BuildPipeline.nativePhase(
                projectDir, cache, lockFile, jdksDir, resolvedMain, extra));
        Goal goal = builder.build();

        ConsoleSpec spec = new ConsoleSpec("Native Build",
                r -> goal.get(BuildPipeline.LAYOUT)
                        .map(l -> "Built native binary " + l.nativeBinary().getFileName())
                        .orElse("Built native binary"),
                r -> "Native build failed");
        GoalResult result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), cache, spec,
                BuildCommand.buildTarget(buildFile, projectDir));

        if (result.success()) return 0;
        for (GoalResult.Diagnostic d : result.errors()) {
            if ("native".equals(d.code()) && d.message().contains("main class")) return 64;
        }
        var testResult = goal.get(BuildPipeline.TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    // --- helpers -------------------------------------------------------------

    /**
     * A member qualifies for native compilation if it declares a {@code main}
     * class and has not explicitly disabled native ({@code native = false}).
     */
    static boolean isNativeEligible(JkBuild build) {
        JkBuild.Project p = build.project();
        return p.main() != null && !p.main().isBlank()
                && p.nativeMode() != JkBuild.NativeMode.DISABLED;
    }

    private String resolveMain(Path buildFile) {
        if (mainClass != null && !mainClass.isBlank()) return mainClass;
        try {
            return ImageConfigParser.parse(buildFile).mainClass();
        } catch (Exception ignored) {
            return null; // fall back to [project] main in the native phase
        }
    }
}
