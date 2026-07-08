// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.PathDisplay;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.WorkspaceMerge;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.runtime.LockGoals;
import dev.jkbuild.util.JkDirs;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code jk update} — re-resolve declared dependencies and overwrite {@code jk.lock}. Same pipeline
 * as {@code jk lock}; the difference is intent: {@code lock} is "make sure a lock exists", {@code
 * update} is "throw away whatever I have and resolve fresh."
 *
 * <p>For workspace roots, updating cascades to each declared module in declaration order, writing a
 * fresh {@code jk.lock} alongside each module's {@code jk.toml}.
 *
 * <p>{@code --precise &lt;coord&gt;@&lt;ver&gt;} per PRD §6 is accepted but a no-op until selective
 * resolution lands.
 *
 * <p>{@code --git [&lt;name&gt;]} re-resolves git dependencies only (one by name, or every git dep
 * when no name is given) — every ref type, tag/rev/branch alike, is pinned in {@code jk.lock} and
 * only moves forward here or via {@code jk fetch}. Every other dependency's locked version is left
 * exactly as it was: the full solver runs (reusing the normal resolve pipeline), then the result is
 * spliced against the previous lock so only the targeted git artifact(s) actually change.
 *
 * <p><b>Engine-hosted</b> (Wave 1 of the slim-client migration): the re-resolve (and the {@code
 * --git} splice) runs inside the resident engine, riding {@code jk lock}'s wire vocabulary ({@link
 * EngineClient#runUpdate}/{@link EngineClient#runUpdateGitOnly}); the shared goal machinery lives
 * in {@link LockGoals} so the test-only in-process path runs the identical pipeline.
 */
public final class UpdateCommand implements CliCommand {

    private String precise;
    private List<String> features = List.of();
    private boolean noDefaultFeatures;
    private URI repoUrl;
    private Path cacheDir;
    private GlobalOptions global;

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String description() {
        return "Propose version upgrades for declared dependencies";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<coord>@<ver>", "Pin one coord@ver (not yet implemented).", "--precise"),
                Opt.value("<a,b,...>", "Activate listed features beyond defaults.", "--features")
                        .splitOn(","),
                Opt.flag("Don't activate the project's defaults.", "--no-default-features"),
                Opt.value(
                                "[<name>]",
                                "Re-resolve one git dependency by its declared name, or every git"
                                        + " dependency when no name is given — leaving every other"
                                        + " dependency's locked version untouched.",
                                "--git")
                        .withFallback("*"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url")
                        .hide(),
                Opt.value(
                                "<dir>",
                                "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide());
    }

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; a real {@code jk update} invocation always
     * engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.precise = in.value("precise").orElse(null);
        this.features = in.values("features");
        this.noDefaultFeatures = in.isSet("no-default-features");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (!Files.exists(dir.resolve("jk.toml"))) {
            CliOutput.err("jk update: no jk.toml in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        if (precise != null && !precise.isBlank()) {
            CliOutput.err("jk update: --precise is recognized but not yet implemented; "
                    + "performing a full re-resolve instead.");
        }

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        String gitTarget = null;
        if (in.has("git")) {
            String target = in.value("git").orElse("*");
            gitTarget = "*".equals(target) ? null : target;
        }

        if (engineDisabledForTests()) {
            return runInProcess(dir, cache, in.has("git"), gitTarget);
        }
        return in.has("git") ? runHostedGitOnly(dir, cache, gitTarget) : runHosted(dir, cache);
    }

    // ---- engine-hosted paths -------------------------------------------------

    private EngineClient.UpdateRequest updateRequest(Path dir, Path cache) {
        var session = dev.jkbuild.config.SessionContext.current();
        return new EngineClient.UpdateRequest(
                dir, cache, features, noDefaultFeatures, repoUrl, session.offline(), session.force(), global.verbose);
    }

    /** Hosted full re-resolve: one console listener per cascade module, summary line per lockfile. */
    private int runHosted(Path dir, Path cache) {
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        EngineClient.LockHandler handler = new EngineClient.LockHandler() {
            @Override
            public GoalListener onModuleStart(String moduleDir, String coord, List<Phase> phases) {
                return GoalConsole.chooseConsoleListener("update", phases, mode);
            }

            @Override
            public void onModuleFinish(String moduleDir, GoalResult result, EngineClient.LockCounts counts) {
                if (result.success() && !global.outputIsJson()) {
                    printUpdatedLine(Path.of(moduleDir).resolve("jk.lock"), (int) counts.packages());
                }
            }
        };

        EngineClient.LockOutcome outcome;
        try {
            outcome = EngineClient.runUpdate(dev.jkbuild.engine.EnginePaths.current(), updateRequest(dir, cache), handler);
        } catch (java.io.IOException e) {
            CliOutput.err("jk update: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        for (String err : outcome.errors()) {
            CliOutput.err("jk update: " + err);
        }
        return outcome.exitCode();
    }

    /** Hosted {@code --git} splice: no goal events — the terminal carries the refreshed count. */
    private int runHostedGitOnly(Path dir, Path cache, String gitTarget) {
        EngineClient.LockOutcome outcome;
        try {
            outcome = EngineClient.runUpdateGitOnly(
                    dev.jkbuild.engine.EnginePaths.current(), updateRequest(dir, cache), gitTarget);
        } catch (java.io.IOException e) {
            CliOutput.err("jk update: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        for (String err : outcome.errors()) {
            CliOutput.err("jk update: " + err);
        }
        if (outcome.success() && !global.outputIsJson()) {
            printGitSummary(outcome.refreshed());
        }
        return outcome.exitCode();
    }

    // ---- test-only in-process path (identical pipeline via LockGoals) --------

    private int runInProcess(Path dir, Path cache, boolean gitOnly, String gitTarget) throws Exception {
        JkBuild root;
        try {
            root = JkBuildParser.parse(dir.resolve("jk.toml"));
        } catch (RuntimeException e) {
            CliOutput.err("jk update: " + e.getMessage());
            return Exit.CONFIG;
        }

        // `jk update --git [<name>]`: re-resolve git dependencies only, leaving every
        // other dependency's locked version untouched.
        if (gitOnly) {
            LockGoals.GitUpdateOutcome outcome =
                    LockGoals.updateGitOnly(dir, root, cache, repoUrl, features, !noDefaultFeatures, gitTarget);
            if (outcome.exitCode() != 0) {
                CliOutput.err("jk update: " + outcome.error());
                return outcome.exitCode();
            }
            if (!global.outputIsJson()) {
                printGitSummary(outcome.refreshed());
            }
            return 0;
        }

        // When updating a workspace module directly, filter sibling-internal deps.
        JkBuild effectiveRoot = LockGoals.applyWorkspaceContextIfModule(dir, root);

        // Re-resolve the current directory (root or standalone project).
        int result = updateSingleProject(dir, effectiveRoot, cache);
        if (result != 0) return result;

        // Cascade: re-resolve each declared workspace module in declaration order.
        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules;
            try {
                modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
            } catch (RuntimeException e) {
                CliOutput.err("jk update: " + e.getMessage());
                return Exit.CONFIG;
            }
            for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                Path moduleDir = entry.getKey();
                JkBuild rawModule = entry.getValue();
                JkBuild effectiveModule = WorkspaceMerge.applyToModule(effectiveRoot, rawModule, modules.values());
                int moduleResult = updateSingleProject(moduleDir, effectiveModule, cache);
                if (moduleResult != 0) return moduleResult;
            }
        }
        return 0;
    }

    private int updateSingleProject(Path dir, JkBuild effective, Path cache) throws Exception {
        Path lockFile = dir.resolve("jk.lock");
        Goal goal = LockGoals.updateGoal(dir, effective, cache, repoUrl, features, !noDefaultFeatures);

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) {
            return LockGoals.failureExitCode(result);
        }

        Lockfile lock = goal.get(LockGoals.LOCKFILE).orElseThrow();
        if (!global.outputIsJson()) {
            printUpdatedLine(lockFile, lock.artifacts().size());
        }
        return 0;
    }

    // ---- shared rendering helpers --------------------------------------------

    /** {@code ✓ Updated: path/to/jk.lock › N packages} — shared by the hosted and in-process paths. */
    private void printUpdatedLine(Path lockFile, int packages) {
        var th = Theme.active();
        CliOutput.out(Theme.colorize(Glyphs.CHECK, th.success())
                + " Updated: "
                + Theme.colorize(PathDisplay.of(lockFile, global.workingDir()), th.path())
                + " "
                + Theme.colorize("›", th.darkGray())
                + " "
                + Theme.colorize(String.valueOf(packages), th.cyan())
                + " package"
                + (packages == 1 ? "" : "s"));
    }

    /** {@code Refreshed N git dependencies.} / {@code No git dependencies to refresh.} */
    private static void printGitSummary(int refreshed) {
        CliOutput.out(
                refreshed == 0
                        ? "No git dependencies to refresh."
                        : "Refreshed " + refreshed + " git dependenc" + (refreshed == 1 ? "y" : "ies") + ".");
    }
}
