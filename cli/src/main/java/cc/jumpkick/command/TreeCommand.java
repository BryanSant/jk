// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.resolver.DependencyTree;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** {@code jk tree} — print the resolved dependency tree. */
public final class TreeCommand implements CliCommand {

    @Override
    public String name() {
        return "tree";
    }

    @Override
    public String description() {
        return "Print the resolved dependency tree";
    }

    @Override
    public List<Opt> options() {
        // --flat / --scope are accepted as unique prefixes of --flatten / --scopes (no explicit alias).
        return List.of(
                Opt.value("<depth>", "Maximum tree depth. Default: unlimited.", "--depth"),
                Opt.flag("Flatten each scope to a sorted, deduped list.", "--flatten"),
                Opt.flag("Blend all scopes into one tree, one badge row.", "--stack"),
                Opt.value("<scopes>", "Scopes to show, in order; meta: exec/run/all.", "--scopes"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        Integer depth = in.value("depth").map(Integer::parseInt).orElse(null);
        boolean flatten = in.isSet("flatten");
        boolean stack = in.isSet("stack");

        // --scopes: an explicit, ordered subset of scopes to display.
        List<Scope> scopes = null;
        var scopesArg = in.value("scopes");
        if (scopesArg.isPresent()) {
            List<String> tokens = Arrays.stream(scopesArg.get().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (tokens.isEmpty()) {
                CliOutput.err("jk tree: --scopes requires at least one scope (valid: " + validScopes() + ")");
                return Exit.CONFIG;
            }
            Set<Scope> ordered = new LinkedHashSet<>();
            for (String token : tokens) {
                List<Scope> expanded = resolveScopeToken(token);
                if (expanded == null) {
                    CliOutput.err("jk tree: invalid scope '" + token + "' (valid: " + validScopes() + ")");
                    return Exit.CONFIG;
                }
                ordered.addAll(expanded);
            }
            scopes = new ArrayList<>(ordered);
        }
        Path dir = new GlobalOptions().workingDir();
        var proj = ProjectContext.require(dir, "tree").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        Path lockFile = proj.lockFile();
        if (!proj.isLocked()) {
            CliOutput.err(
                    "jk tree: no jk.lock in " + cc.jumpkick.cli.PathDisplay.styledRaw(dir) + " (run `jk lock` first)");
            return Exit.CONFIG;
        }

        int max = depth != null ? depth : Integer.MAX_VALUE;

        // Header: a leading blank line, then a left-flush green powerline chip
        // " ● Dependencies Tree " — white on PLAN_BLUE with a PLAN_BLUE cap.
        // Uses the filled-circle bullet (●) consistent with the root-node glyph.
        boolean nerdfont = cc.jumpkick.config.GlobalConfig.nerdfont();
        Theme t = Theme.active();
        boolean ansi = t.isAnsi();

        if (ansi) {
            String title = " ≡ Dependencies Tree ";
            String header = nerdfont
                    ? Theme.colorize(title, t.pipelineChip())
                            + Theme.colorize(cc.jumpkick.cli.tui.Glyphs.SEGMENT_END_NERD, t.bright(t.planBadgeColor()))
                    : Theme.colorize(title, t.pipelineChip());
            CliOutput.out();
            CliOutput.out(header);
        } else {
            CliOutput.out();
            CliOutput.out(" - Dependencies Tree:");
        }

        // Composite-aware: walks path deps' own trees too (anchored at `dir`). The walk runs
        // engine-side (thin client) with marker-tag styling; this client substitutes its Theme.
        List<String> scopeNames = scopes == null
                ? List.of()
                : scopes.stream().map(sc -> sc.name().toLowerCase(Locale.ROOT).replace('_', '-')).toList();
        String tagged;
        try {
            tagged = engineDisabledForTests()
                    ? cc.jumpkick.cli.engine.InProcessEngine.require().treeRender(dir, max, flatten, stack, scopeNames)
                    : cc.jumpkick.cli.engine.EngineClient.treeRender(
                            cc.jumpkick.engine.EnginePaths.current(), dir, max, flatten, stack, scopeNames);
        } catch (IOException | RuntimeException e) {
            CliOutput.err("jk tree: " + e.getMessage());
            return Exit.CONFIG;
        }
        String rendered = DependencyTree.applyStyling(tagged, styling(nerdfont, ansi));
        // Split root coord from tree body so we can insert a separator between them.
        int nl = rendered.indexOf('\n');
        if (nl >= 0) {
            CliOutput.out(rendered.substring(0, nl));
            CliOutput.out(ansi ? " " + Theme.colorize("│", t.darkGray()) : " |");
            CliOutput.outRaw(indentBody(rendered.substring(nl + 1), false));
        } else {
            CliOutput.outRaw(rendered);
        }
        if (rendered.contains(DependencyTree.MISSING_SUFFIX)) {
            CliOutput.out();
            CliOutput.out(ansi
                    ? "Some dependencies are missing from your local cache. Run "
                            + Theme.colorize("jk lock", t.warning())
                    : "Some dependencies are missing from your local cache. Run `jk lock`");
        }
        return 0;
    }

    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "cc.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }

    /**
     * The {@code exec}/{@code run} meta-scope: the scopes that form the classpath needed to run the
     * project, in display order. Sourced from {@link ClasspathResolver#RUNTIME} so it stays in sync
     * with the real run classpath.
     */
    private static final List<Scope> EXEC_SCOPES = Arrays.stream(Scope.values())
            .filter(ClasspathResolver.RUNTIME::contains)
            .toList();

    /**
     * Resolve a user-supplied scope token (case-insensitive) to one or more scopes: {@code
     * exec}/{@code run} expand to the run classpath; any other token is a single scope. Returns null
     * if the token is not a valid scope or meta-scope.
     */
    private static List<Scope> resolveScopeToken(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        if (t.equals("all")) return DependencyTree.defaultScopeOrder();
        if (t.equals("exec") || t.equals("run")) return EXEC_SCOPES;
        Scope scope = coerceScope(t);
        return scope == null ? null : List.of(scope);
    }

    /**
     * Coerce a user-supplied scope token (case-insensitive) to a {@link Scope}, or null if invalid.
     */
    private static Scope coerceScope(String token) {
        try {
            // fromCanonical handles hyphenated scopes ("test-dev"); fall back to the
            // enum-name form ("TEST_DEV") for users typing underscores.
            return Scope.fromCanonical(token.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            try {
                return Scope.valueOf(token.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e2) {
                return null;
            }
        }
    }

    /**
     * Comma-separated list of valid scope names (incl. the {@code exec}/{@code run}/{@code all}
     * meta-scopes).
     */
    private static String validScopes() {
        return Arrays.stream(Scope.values())
                        .map(s -> s.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.joining(", "))
                + ", exec/run, all";
    }

    /**
     * Indents every line below the root node by one space, so the tree body sits one column in from
     * the {@code ●} root bullet. The first line (the root coord) and any blank lines are left
     * untouched.
     */
    private static String indentBody(String rendered) {
        return indentBody(rendered, true);
    }

    /**
     * Indents tree body lines by one space. When {@code skipFirst} is true (the full rendered
     * string including root coord is passed), the first line is left untouched. When false (only
     * the body after the root coord is passed), every non-empty line gets a leading space.
     */
    private static String indentBody(String rendered, boolean skipFirst) {
        String[] lines = rendered.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            if ((!skipFirst || i > 0) && !lines[i].isEmpty()) sb.append(' ');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * Color pattern for the Maven coordinate — the canonical {@code
     * [blue]group[/]:[cyan]artifact[/]:[bright-blue]version[/]} from {@link Coords}. Rails get the
     * same dim dark-gray the wizard uses for its settled rails. {@link Theme#colorize} respects
     * {@code --color} / {@code NO_COLOR} / dumb terminals, so escapes are dropped cleanly when color
     * is off.
     */
    private static DependencyTree.Styling styling(boolean nerdfont, boolean ansi) {
        if (!ansi) {
            // No-ANSI: replace all Unicode connectors with ASCII equivalents,
            // use [scope] bracket badges, * root bullet, plain uncolored coords.
            UnaryOperator<String> asciiRail = s -> switch (s) {
                case "├─"  -> "+-";
                case "╰─"  -> "`-";
                case "├─ " -> "+- ";
                case "╰─ " -> "`- ";
                case "│  " -> "|  ";
                case "   " -> "   ";
                case "●"   -> "*";
                default    -> s;
            };
            UnaryOperator<String> plain = UnaryOperator.identity();
            // Back-reference rows: connector + coord + " ⎋" all arrive as one string.
            // Replace Unicode connectors and drop the ⎋ marker (no color = no dim cue).
            UnaryOperator<String> asciiReference = s -> s
                    .replace("╰─ ", "`- ")
                    .replace("├─ ", "+- ")
                    .replace(" ⎋", "");
            UnaryOperator<String> asciiBadge = s -> "[" + s + "]";
            UnaryOperator<String> asciiRoot = gav -> " * " + gav;
            return new DependencyTree.Styling(
                    asciiRail, plain, plain, plain, asciiReference, asciiBadge, plain, asciiRoot);
        }
        // Scope section badge: a rounded pill (Nerd Font) or space-padded chip.
        UnaryOperator<String> scopeBadge = s -> cc.jumpkick.cli.tui.Badge.pill(s, nerdfont);
        Theme t = Theme.active();
        // Root-line: ● bullet (dark-gray) + bold coord colors — no pill or background.
        return new DependencyTree.Styling(
                s -> Theme.colorize(s, t.darkGray()),
                s -> Theme.colorize(s, Coords.groupStyle()),
                s -> Theme.colorize(s, Coords.artifactStyle()),
                s -> Theme.colorize(s, Coords.versionStyle()),
                // ⎋ back-reference rows: the whole entry in bright-black (= darkGray).
                s -> Theme.colorize(s, t.darkGray()),
                scopeBadge,
                TreeCommand::boldCoord);
    }

    /**
     * The root project coordinate in bold — {@code group:artifact:version}, each segment bold and in
     * its usual {@link Coords} color. Bold must be baked into each segment's style (a wrapping bold
     * escape is cancelled by every segment's color reset). Input is the plain {@code
     * group:artifact:version}.
     */
    private static String boldCoord(String gav) {
        String[] p = gav.split(":", 3);
        if (p.length < 3) return Theme.colorize(gav, Coords.groupStyle().bold());
        return Theme.colorize(p[0], Coords.groupStyle().bold())
                + ":"
                + Theme.colorize(p[1], Coords.artifactStyle().bold())
                + ":"
                + Theme.colorize(p[2], Coords.versionStyle().bold());
    }
}
