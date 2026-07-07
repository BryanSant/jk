// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.ProjectContext;
import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.runtime.BuildPlanForecast;
import dev.jkbuild.runtime.BuildService;
import dev.jkbuild.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code jk explain} — forecast the build (the same plan the build driver uses, via {@link
 * BuildService#explain}): every module (workspace root + modules + transitive {@code path} /
 * branch-git deps) in dependency order, and — via {@link BuildPlanForecast} — what each of its phases
 * (compile → test → package) would do when {@code jk build} runs: restore from cache or do real
 * work. Fully-cached modules collapse to a summary line; modules that will rebuild expand to their
 * phase sub-tree ({@code --verbose} expands all). {@code --run} executes the plan ({@code jk
 * build}). Per PRD §25.1.
 */
public final class ExplainCommand implements CliCommand {

    @Override
    public String name() {
        return "explain";
    }

    @Override
    public String description() {
        return "Print the planned build (units, order, cache hit/miss)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Build the plan instead of printing it (`jk build`).", "--run"),
                Opt.flag("Expand every module's phases, even cached ones.", "--verbose", "-v"),
                Opt.flag("Estimate the ETA for a serial build (one module at a time).", "--no-parallel"),
                Opt.flag("", "--parallel").hide(),
                Opt.flag("Estimate the ETA with tests running concurrently across modules.", "--parallel-tests"),
                // The plan-affecting options `jk build` accepts — forecasting `jk build <flags>`
                // means feeding the same inputs to the shared estimate (and, with --run, to build).
                Opt.value("<name>", "Forecast with a build profile applied. Default: auto (ci on CI).", "--profile"),
                Opt.value("<N>", "Forecast with N test-runner JVMs forked in parallel. Default 1.", "-w", "--workers"),
                Opt.flag("Forecast a build that skips compiling and running tests.", "--skip-tests"),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir").hide(),
                Opt.value(
                                "<dir>",
                                "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide());
    }

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#daemonDisabledForTests()} for why this exists; every real {@code jk explain}
     * invocation goes through the daemon.
     */
    private static boolean daemonDisabledForTests() {
        return Boolean.getBoolean("jk.test.noDaemon");
    }

    @Override
    public int run(Invocation in) throws Exception {
        if (in.isSet("run")) {
            return new BuildCommand().run(in); // forwards --cache-dir; build options default
        }
        GlobalOptions global = GlobalOptions.from(in);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        Path startDir = global.workingDir();
        var proj = ProjectContext.require(startDir, "explain").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        JkBuild entry = JkBuildParser.parse(buildFile);
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        // Forecast the build through the engine facade — resolve the graph and run the truthful
        // per-phase plan, returning a front-end-safe view (modules + edges + concurrency width).
        // Daemon-hosted like `jk build`/`jk test`, except in the fast unit-test suite (no real jk
        // binary/daemon available there — see BuildCommand.daemonDisabledForTests()).
        BuildService.ExplainPlan plan = daemonDisabledForTests()
                ? BuildService.explain(startDir, entry, cache)
                : dev.jkbuild.cli.daemon.DaemonClient.explain(dev.jkbuild.daemon.DaemonPaths.current(), startDir, cache);
        if (plan.hasErrors()) {
            for (String err : plan.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            return Exit.CONFIG;
        }

        Theme t = Theme.active();
        boolean ansi = t.isAnsi();
        boolean nerdfont = dev.jkbuild.config.GlobalConfig.nerdfont();
        // On a TTY, wrap the cached-module list to the terminal width; piped output gets
        // the full list on one line (MAX_VALUE → never wraps).
        int width = dev.jkbuild.cli.run.GoalConsole.isInteractiveTerminal()
                ? dev.jkbuild.cli.tui.CommandManager.detectColumns()
                : Integer.MAX_VALUE;

        // Forecast every module's full phase pipeline (compile → test → package),
        // truthfully — see BuildPlanForecast.
        List<BuildPlanForecast.Module> modules = plan.modules();
        boolean all = in.isSet("verbose");
        int total = modules.size();
        long rebuild = modules.stream().filter(BuildPlanForecast.Module::dirty).count();

        // Predicted wall-clock for the build. Each module's predicted weight (≈150 ms
        // each, cascade reserved via forceRebuild) feeds a schedule-aware estimate that
        // mirrors `jk build`: serial (--no-parallel) sums everything; otherwise the
        // parallel graph build overlaps independent modules, so we take the critical
        // path / throughput / serial-test bound (see EffortWeights.scheduleMillis).
        // Computed even for an all-cached plan: re-parsing every build file and
        // re-checking stamps/CAS across the workspace is a real couple of seconds.
        boolean serial = in.isSet("no-parallel") && !in.isSet("parallel") && !in.isSet("parallel-tests");
        boolean parallelTests = in.isSet("parallel-tests");
        // The plan-affecting options `jk build` reads, forecast with the same defaults build uses
        // (jdksDir=null → full JDK probe chain, workers=1, skipTests=false) so a bare `jk explain`
        // predicts exactly what a bare `jk build` would do.
        int workers = in.value("workers").map(Integer::parseInt).orElse(1);
        boolean skipTests = in.isSet("skip-tests");
        String profile = in.value("profile").orElse(null);
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        long etaMillis = 0;
        try {
            List<dev.jkbuild.runtime.EffortWeights.ModuleCost> costs = new ArrayList<>();
            // All modules of this build graph — the project/workspace set each module's prediction
            // borrows a learned rate from when it has no history of its own (EffortWeights.learned).
            Set<Path> projectModules =
                    modules.stream().map(BuildPlanForecast.Module::dir).collect(java.util.stream.Collectors.toSet());
            for (BuildPlanForecast.Module m : modules) {
                Path mdir = m.dir();
                // Assemble each module's goal exactly as `jk build` does: the shared Inputs factory,
                // core phases + declared tails (native-image / OCI / shadow). Same goal in, same
                // cost out — so the ETA can't diverge from what build calibrates to.
                var inputs = BuildPlanForecast.inputsFor(
                        mdir, cache, workers, jdksDir, profile, skipTests, global.verbose, projectModules);
                var builder = dev.jkbuild.runtime.BuildPipeline.coreBuilder(inputs, m.dirty());
                dev.jkbuild.runtime.BuildPipeline.appendDeclaredTails(builder, inputs);
                var goal = builder.build();
                costs.add(dev.jkbuild.runtime.EffortWeights.costOf(
                        mdir, plan.edges().getOrDefault(mdir, Set.of()), goal));
            }
            int concurrency = serial
                    ? 1
                    : dev.jkbuild.worker.HeapPlan.requestedJvms(
                            plan.maxReadyWidth(), workers, parallelTests, Runtime.getRuntime()
                                    .availableProcessors());
            // Weight→ms conversion, PER MODULE: a module whose own dir has learned timings converts
            // at MS_PER_WEIGHT (its learned rates were recorded relative to that constant, so it
            // round-trips this host exactly); a module with no learned entries of its own falls back
            // to the static reference-frame weights, so it converts at this host's measured
            // calibration instead of the MS_PER_WEIGHT reference constant (~4× hot on a fast machine).
            // Per-module, not one workspace-wide rate: a brand-new module beside already-built ones is
            // the common mixed case, and a single rail mis-prices one side or the other. The one-time
            // host probe (Calibration.ensure) runs only when some module is actually cold — the
            // sanctioned exception to explain being a pure dry run.
            dev.jkbuild.runtime.PhaseTimings timings = dev.jkbuild.runtime.PhaseTimings.load(cache);
            java.util.function.Predicate<Path> warm =
                    dir -> timings.hasTimingsFor(java.util.List.of(dir.toString()));
            double coldRate = costs.stream().allMatch(c -> warm.test(c.dir()))
                    ? dev.jkbuild.runtime.EffortWeights.MS_PER_WEIGHT
                    : dev.jkbuild.runtime.Calibration.ensure(jdksDir).msPerWeight();
            etaMillis = dev.jkbuild.runtime.EffortWeights.scheduleMillis(
                    costs,
                    concurrency,
                    serial,
                    parallelTests,
                    dir -> warm.test(dir) ? dev.jkbuild.runtime.EffortWeights.MS_PER_WEIGHT : coldRate);
        } catch (RuntimeException e) {
            etaMillis = 0; // never fail explain over the estimate
        }

        // Header: a dark royal blue (#0F4786) " ≡ Build Plan " chip, capped by a matching ▶
        // segment arrow when nerdfont, then the build-time estimate (yellow).
        String header = !ansi ? " - Build Plan "
                : (nerdfont
                        ? Theme.colorize(" ≡ Build Plan ", t.planBadge())
                                + Theme.colorize(dev.jkbuild.cli.tui.Glyphs.SEGMENT_END_NERD, t.bright(t.planBadgeColor()))
                        : Theme.colorize(" ≡ Build Plan ", t.planBadge()));
        String estimate = etaMillis == 0
                ? "Build time " + Theme.colorize("unknown", t.warning())
                : "Build time estimate " + Theme.colorize("~" + fmtDuration(etaMillis), t.warning());
        CliOutput.out();
        CliOutput.out(header + " " + estimate);
        // Root node: ● bullet, then the entry project's group:artifact in bold.
        String rootBullet = ansi ? Theme.colorize("●", t.darkGray()) : "*";
        String coord = entry.project().group() + ":" + entry.project().name();
        CliOutput.out(" "
                + rootBullet
                + " "
                + (ansi ? boldCoord(coord, t) : coord));

        // Workspace-wide stats directly under the root bullet.
        int totalModules = modules.size();
        int totalSources =
                modules.stream().mapToInt(BuildPlanForecast.Module::sourceCount).sum();
        int totalTests =
                modules.stream().mapToInt(BuildPlanForecast.Module::testCount).sum();
        int totalJars = (int)
                modules.stream().filter(BuildPlanForecast.Module::producesJar).count();
        int totalImages = (int)
                modules.stream().filter(BuildPlanForecast.Module::producesImage).count();
        String rootPfx = ansi ? " " + Theme.colorize("│", t.darkGray()) + " · " : " | - ";
        if (totalModules > 1) CliOutput.out(rootPfx + "Modules: " + String.format("%,d", totalModules));
        CliOutput.out(rootPfx + "Sources: " + fmtCount(totalSources, "file", "files"));
        CliOutput.out(rootPfx + "Tests: " + fmtCount(totalTests, "test", "tests"));
        if (totalJars > 0) CliOutput.out(rootPfx + "Packages: " + fmtCount(totalJars, "jar", "jars"));
        if (totalImages > 0) CliOutput.out(rootPfx + "Containers: " + fmtCount(totalImages, "image", "images"));

        // Partition the topo order by cache status: every fully-cached module (wherever
        // it sits in the order) collapses into the "Fully Cached" section (names only);
        // only the modules that actually rebuild appear in the detailed "Rebuild" section,
        // each keeping its topo index. --verbose expands every phase (cached ones too).
        boolean verbose = all;
        List<Integer> cachedIdx = new ArrayList<>();
        List<Integer> dirtyIdx = new ArrayList<>();
        for (int i = 0; i < modules.size(); i++) {
            (modules.get(i).dirty() ? dirtyIdx : cachedIdx).add(i);
        }

        if (!cachedIdx.isEmpty()) {
            boolean lastSection = dirtyIdx.isEmpty();
            String sectionConnector = lastSection ? "╰─" : "├─";
            String sectionBadge = ansi
                    ? dev.jkbuild.cli.tui.Badge.pill("Fully Cached", nerdfont)
                    : " [Fully Cached]";
            CliOutput.out(" "
                    + (ansi ? Theme.colorize(sectionConnector, t.darkGray()) : (lastSection ? "`-" : "+-"))
                    + sectionBadge);
            String childPrefix = ansi
                    ? " " + Theme.colorize(lastSection ? "   " : "│  ", t.darkGray())
                    : " " + (lastSection ? "   " : "|  ");
            if (verbose) {
                for (int j = 0; j < cachedIdx.size(); j++) {
                    int i = cachedIdx.get(j);
                    renderModuleRow(modules.get(i), i + 1, j == cachedIdx.size() - 1, childPrefix, nerdfont, true, t, ansi);
                }
            } else {
                List<String> names = new ArrayList<>();
                for (int i : cachedIdx)
                    names.add(":" + shortName(modules.get(i).coord()));
                // Wrap the full list across lines (no truncation): the first line hangs off
                // a "╰─ " connector; continuations align under the first name.
                List<String> lines = wrapNames(names, Math.max(20, width - 7));
                String cont = childPrefix + "   "; // align past "╰─ " / "`- "
                String firstConnector = ansi ? Theme.colorize("╰─ ", t.darkGray()) : "`- ";
                for (int li = 0; li < lines.size(); li++) {
                    CliOutput.out((li == 0 ? childPrefix + firstConnector : cont)
                            + renderCachedNames(lines.get(li), t));
                }
            }
        }
        if (!dirtyIdx.isEmpty()) {
            String rebuildBadge = ansi
                    ? dev.jkbuild.cli.tui.Badge.pill("Rebuild", nerdfont)
                    : " [Rebuild]";
            CliOutput.out(" "
                    + (ansi ? Theme.colorize("╰─", t.darkGray()) : "`-")
                    + rebuildBadge);
            String secPfx = ansi ? "    " + Theme.colorize("│", t.darkGray()) + " · " : "    | - ";
            int dirtyModules = dirtyIdx.size();
            int dirtySources = modules.stream()
                    .filter(BuildPlanForecast.Module::dirty)
                    .mapToInt(BuildPlanForecast.Module::sourceCount)
                    .sum();
            int dirtyTests = modules.stream()
                    .filter(BuildPlanForecast.Module::dirty)
                    .mapToInt(BuildPlanForecast.Module::testCount)
                    .sum();
            int dirtyJars = (int)
                    modules.stream().filter(m -> m.dirty() && m.producesJar()).count();
            int dirtyImages = (int)
                    modules.stream().filter(m -> m.dirty() && m.producesImage()).count();
            if (totalModules > 1)
                CliOutput.out(
                        secPfx + "Modules: " + String.format("%,d", dirtyModules) + pct(dirtyModules, totalModules));
            CliOutput.out(
                    secPfx + "Sources: " + fmtCount(dirtySources, "file", "files") + pct(dirtySources, totalSources));
            CliOutput.out(
                    secPfx + "Tests: " + fmtCount(dirtyTests, "test", "tests") + pct(dirtyTests, totalTests));
            if (totalJars > 0)
                CliOutput.out(
                        secPfx + "Packages: " + fmtCount(dirtyJars, "jar", "jars") + pct(dirtyJars, totalJars));
            if (totalImages > 0)
                CliOutput.out(secPfx
                        + "Containers: "
                        + fmtCount(dirtyImages, "image", "images")
                        + pct(dirtyImages, totalImages));
            for (int j = 0; j < dirtyIdx.size(); j++) {
                int i = dirtyIdx.get(j);
                renderModuleRow(modules.get(i), i + 1, j == dirtyIdx.size() - 1, "    ", nerdfont, verbose, t, ansi);
            }
        }
        return 0;
    }

    /** "1m 20s" / "8s" / "<1s" — coarse predicted-duration formatting for the plan summary. */
    private static String fmtDuration(long millis) {
        if (millis <= 0) return "0s";
        long s = millis / 1000; // floor: don't over-state
        if (s == 0) return "<1s"; // a sub-second cache-verify pass
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    /** Longest phase-name column (e.g. {@code package-shadow}, {@code compile-kotlin}). */
    private static final int PHASE_COL = 14;

    /**
     * Render one module row under a section: {@code prefix} + connector + index badge + coordinate,
     * then its phase sub-tree. The verdict is implied by the enclosing section (Fully Cached /
     * Rebuild) and the origin / dependency edges are omitted as noise. Phases render when the module
     * rebuilds, or always under {@code verbose}.
     */
    private static void renderModuleRow(
            BuildPlanForecast.Module m,
            int idx,
            boolean last,
            String prefix,
            boolean nerdfont,
            boolean verbose,
            Theme t,
            boolean ansi) {
        String moduleConnector = ansi
                ? Theme.colorize((last ? "╰" : "├") + "─", t.darkGray())
                : (last ? "`-" : "+-");
        String moduleBadge = ansi
                ? dev.jkbuild.cli.tui.Badge.pill(String.format("%02d", idx), nerdfont)
                : " [" + String.format("%02d", idx) + "]";
        CliOutput.out(prefix
                + moduleConnector
                + moduleBadge
                + ' '
                + (ansi ? coloredCoord(m.coord(), t) : m.coord()));

        if (m.dirty() || verbose) {
            String spine = ansi
                    ? prefix + (last ? "   " : Theme.colorize("│", t.darkGray()) + "  ")
                    : prefix + (last ? "   " : "|  ");
            List<BuildPlanForecast.Phase> ph = m.phases();
            // Pad each □ phase's verb to the widest in this module so the · column lines up.
            int verbCol = 0;
            for (BuildPlanForecast.Phase p : ph) {
                if (!p.cached()) verbCol = Math.max(verbCol, verbWidth(p.text()));
            }
            for (int k = 0; k < ph.size(); k++) {
                boolean lp = k == ph.size() - 1;
                String phaseConnector = ansi
                        ? Theme.colorize(lp ? "╰─ " : "├─ ", t.darkGray())
                        : (lp ? "`- " : "+- ");
                String phaseName = ansi
                        ? Theme.colorize(padRight(ph.get(k).name(), PHASE_COL), t.brightWhite())
                        : padRight(ph.get(k).name(), PHASE_COL);
                CliOutput.out(spine
                        + phaseConnector
                        + phaseName
                        + "  "
                        + renderStatus(ph.get(k), verbCol, t, ansi));
            }
        }
    }

    /** Visible width of a phase verb — the text before {@code " · "} (or the whole text). */
    private static int verbWidth(String text) {
        int sep = text.indexOf(" · ");
        return (sep < 0 ? text : text.substring(0, sep)).length();
    }

    /** The entry project's {@code group:artifact} in bold, each segment in its coord color. */
    private static String boldCoord(String coord, Theme t) {
        int colon = coord.indexOf(':');
        if (colon < 0) return Theme.colorize(coord, t.coordName().bold());
        return Theme.colorize(coord.substring(0, colon), t.coordGroup().bold())
                + ":"
                + Theme.colorize(coord.substring(colon + 1), t.coordName().bold());
    }

    /** The artifact half of a {@code group:artifact} coordinate. */
    private static String shortName(String coord) {
        int c = coord.indexOf(':');
        return c < 0 ? coord : coord.substring(c + 1);
    }

    /** Color a comma-list of {@code :name} cached-module refs (the {@code …+N more…} marker dim). */
    private static String renderCachedNames(String elided, Theme t) {
        StringBuilder sb = new StringBuilder();
        String[] pieces = elided.split(", ");
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) sb.append(Theme.colorize(", ", t.darkGray()));
            String p = pieces[i];
            if (p.matches("…\\+\\d+ more…")) {
                sb.append(Theme.colorize(p, t.darkGray()));
            } else if (p.startsWith(":")) {
                sb.append(":").append(Theme.colorize(p.substring(1), t.coordName()));
            } else {
                sb.append(Theme.colorize(p, t.coordName()));
            }
        }
        return sb.toString();
    }

    /**
     * A phase's status: {@code ✓ cached <key> · detail} (green) when cached, otherwise {@code □
     * <verb> · <detail>} with the verb in yellow (padded to {@code verbCol} so the {@code ·} lines up
     * across the module's phases), the {@code ·} bright-black, and the trailing detail in italic.
     * When {@code !ansi}: {@code (cached)} / {@code [run]} ASCII equivalents, no color.
     */
    private static String renderStatus(BuildPlanForecast.Phase p, int verbCol, Theme t, boolean ansi) {
        if (p.cached()) {
            if (!ansi) {
                StringBuilder s = new StringBuilder("(cached)");
                if (p.key() != null) s.append(' ').append(p.key());
                if (p.text() != null && !p.text().isEmpty()) s.append(' ').append(p.text());
                return s.toString();
            }
            StringBuilder s = new StringBuilder(Theme.colorize("✓ cached", t.success()));
            if (p.key() != null) s.append(' ').append(Theme.colorize(p.key(), t.path()));
            if (p.text() != null && !p.text().isEmpty())
                s.append(' ').append(Theme.colorize(p.text(), t.darkGray().italic()));
            return s.toString();
        }
        String text = p.text();
        int sep = text.indexOf(" · ");
        String verb = sep < 0 ? text : text.substring(0, sep);
        String detail = sep < 0 ? null : text.substring(sep + 3);
        if (!ansi) {
            StringBuilder s = new StringBuilder("[run] ").append(padRight(verb, verbCol));
            if (detail != null) s.append(" - ").append(detail);
            return s.toString();
        }
        StringBuilder s = new StringBuilder(Theme.colorize("□ ", t.brightWhite()))
                .append(Theme.colorize(padRight(verb, verbCol), t.warning()));
        if (detail != null) {
            s.append(' ')
                    .append(Theme.colorize("·", t.darkGray()))
                    .append(' ')
                    .append(Theme.colorize(detail, t.brightWhite().italic()));
        }
        return s.toString();
    }

    private static String fmtCount(int n, String singular, String plural) {
        return String.format("%,d", n) + " " + (n == 1 ? singular : plural);
    }

    private static String pct(int part, int whole) {
        if (whole <= 0) return "";
        return " - " + (part * 100 / whole) + "%";
    }

    private static String padRight(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    /** {@code group:name} with the group and name in their coordinate colors. */
    private static String coloredCoord(String coord, Theme t) {
        int colon = coord.indexOf(':');
        if (colon < 0) return Theme.colorize(coord, t.coordName());
        return Theme.colorize(coord.substring(0, colon), t.coordGroup())
                + ":"
                + Theme.colorize(coord.substring(colon + 1), t.coordName());
    }

    /**
     * Join {@code units} with {@code ", "} to fit {@code available} visible columns. When the full
     * list is too wide, show as many leading units as fit followed by a {@code …+N more…} marker,
     * where {@code N} is the count of remaining units that didn't fit. {@code available} is
     * effectively unbounded on a non-TTY, so the full list is shown there.
     */
    static String elideDeps(List<String> units, int available) {
        String full = String.join(", ", units);
        if (available <= 0 || units.size() <= 1 || full.length() <= available) return full;
        String best = "…+" + units.size() + " more…"; // marker-only, if even one unit won't fit
        for (int k = 1; k < units.size(); k++) {
            String candidate = String.join(", ", units.subList(0, k)) + ", …+" + (units.size() - k) + " more…";
            if (candidate.length() > available) break; // front grows monotonically
            best = candidate;
        }
        return best;
    }

    /**
     * Greedily pack {@code tokens} into {@code ", "}-joined lines, each at most {@code avail} visible
     * columns wide (the wrap point drops the separator rather than leaving a trailing comma). On a
     * non-TTY {@code avail} is effectively unbounded, so the whole list lands on one line.
     */
    static List<String> wrapNames(List<String> tokens, int avail) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String tok : tokens) {
            if (cur.length() == 0) {
                cur.append(tok);
            } else if (cur.length() + 2 + tok.length() <= avail) {
                cur.append(", ").append(tok);
            } else {
                lines.add(cur.toString());
                cur = new StringBuilder(tok);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }
}
