// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.resolver.DependencyTree;
import java.io.IOException;
import java.nio.file.Files;
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
        return List.of(
                Opt.value("<depth>", "Maximum tree depth. Default: unlimited.", "--depth"),
                Opt.flag("Flatten each scope to a sorted, deduped list.", "--flatten"),
                Opt.flag("", "--flat").hide(),
                Opt.flag("Blend all scopes into one tree, one badge row.", "--stack"),
                Opt.value("<scopes>", "Scopes to show, in order; meta: exec/run/all.", "--scopes"),
                Opt.value("<scopes>", "", "--scope").hide());
    }

    @Override
    public int run(Invocation in) throws IOException {
        Integer depth = in.value("depth").map(Integer::parseInt).orElse(null);
        boolean flatten = in.isSet("flatten") || in.isSet("flat");
        boolean stack = in.isSet("stack");

        // --scopes / --scope: an explicit, ordered subset of scopes to display.
        List<Scope> scopes = null;
        var scopesArg = in.value("scopes").or(() -> in.value("scope"));
        if (scopesArg.isPresent()) {
            List<String> tokens = Arrays.stream(scopesArg.get().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (tokens.isEmpty()) {
                System.err.println("jk tree: --scopes requires at least one scope (valid: " + validScopes() + ")");
                return 2;
            }
            Set<Scope> ordered = new LinkedHashSet<>();
            for (String token : tokens) {
                List<Scope> expanded = resolveScopeToken(token);
                if (expanded == null) {
                    System.err.println("jk tree: invalid scope '" + token + "' (valid: " + validScopes() + ")");
                    return 2;
                }
                ordered.addAll(expanded);
            }
            scopes = new ArrayList<>(ordered);
        }
        Path dir = new GlobalOptions().workingDir();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk tree: no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(dir));
            return 2;
        }
        if (!Files.exists(lockFile)) {
            System.err.println(
                    "jk tree: no jk.lock in " + dev.jkbuild.cli.PathDisplay.styledRaw(dir) + " (run `jk lock` first)");
            return 2;
        }

        JkBuild project = JkBuildParser.parse(buildFile);
        Lockfile lock = LockfileReader.read(lockFile);
        int max = depth != null ? depth : Integer.MAX_VALUE;

        // Header: a leading blank line, then a left-flush green powerline chip
        // " - Dependencies Tree " — white on PLAN_BLUE with a PLAN_BLUE cap.
        // '-' is not a status glyph, so the chip follows the default blue rule.
        boolean nerdfont = dev.jkbuild.config.GlobalConfig.nerdfont();
        Theme t = Theme.active();
        String title = " - Dependencies Tree ";
        String header = nerdfont
                ? Theme.colorize(title, t.goalChip())
                        + Theme.colorize(dev.jkbuild.cli.tui.Glyphs.SEGMENT_END_NERD, t.bright(t.planBadgeColor()))
                : Theme.colorize(title, t.goalChip());
        System.out.println();
        System.out.println(header);
        // Composite-aware: walks path deps' own trees too (anchored at `dir`).
        String rendered = DependencyTree.render(project, lock, dir, max, styling(nerdfont), flatten, scopes, stack);
        System.out.print(indentBody(rendered));
        if (rendered.contains(DependencyTree.MISSING_SUFFIX)) {
            System.out.println();
            System.out.println("Some dependencies are missing from your local cache. Run "
                    + Theme.colorize("jk lock", Theme.active().warning()));
        }
        return 0;
    }

    /**
     * The {@code exec}/{@code run} meta-scope: the scopes that form the classpath
     * needed to run the project, in display order. Sourced from
     * {@link ClasspathResolver#RUNTIME} so it stays in sync with the real run classpath.
     */
    private static final List<Scope> EXEC_SCOPES = Arrays.stream(Scope.values())
            .filter(ClasspathResolver.RUNTIME::contains)
            .toList();

    /**
     * Resolve a user-supplied scope token (case-insensitive) to one or more scopes:
     * {@code exec}/{@code run} expand to the run classpath; any other token is a single
     * scope. Returns null if the token is not a valid scope or meta-scope.
     */
    private static List<Scope> resolveScopeToken(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        if (t.equals("all")) return DependencyTree.defaultScopeOrder();
        if (t.equals("exec") || t.equals("run")) return EXEC_SCOPES;
        Scope scope = coerceScope(t);
        return scope == null ? null : List.of(scope);
    }

    /** Coerce a user-supplied scope token (case-insensitive) to a {@link Scope}, or null if invalid. */
    private static Scope coerceScope(String token) {
        try {
            return Scope.valueOf(token.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Comma-separated list of valid scope names (incl. the {@code exec}/{@code run}/{@code all} meta-scopes). */
    private static String validScopes() {
        return Arrays.stream(Scope.values())
                        .map(s -> s.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.joining(", "))
                + ", exec/run, all";
    }

    /**
     * Indents every line below the root node by one space, so the tree body sits
     * one column in from the {@code ●} root bullet. The first line (the root coord)
     * and any blank lines are left untouched.
     */
    private static String indentBody(String rendered) {
        String[] lines = rendered.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            if (i > 0 && !lines[i].isEmpty()) sb.append(' ');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * Color pattern for the Maven coordinate — the canonical
     * {@code [blue]group[/]:[cyan]artifact[/]:[bright-blue]version[/]} from
     * {@link Coords}. Rails get the same dim dark-gray the wizard uses for its
     * settled rails. {@link Theme#colorize} respects {@code --color} /
     * {@code NO_COLOR} / dumb terminals, so escapes are dropped cleanly when
     * color is off.
     */
    private static DependencyTree.Styling styling(boolean nerdfont) {
        // Scope section badge: a black-on-bright-black chip (a rounded pill with a
        // Nerd Font, a space-padded chip without) — shared with jk explain's index.
        UnaryOperator<String> scopeBadge = s -> dev.jkbuild.cli.tui.Badge.pill(s, nerdfont);
        return new DependencyTree.Styling(
                s -> Theme.colorize(s, Theme.active().darkGray()),
                s -> Theme.colorize(s, Coords.groupStyle()),
                s -> Theme.colorize(s, Coords.artifactStyle()),
                s -> Theme.colorize(s, Coords.versionStyle()),
                // ⎋ back-reference rows: the whole entry in bright-black (= darkGray).
                s -> Theme.colorize(s, Theme.active().darkGray()),
                scopeBadge,
                TreeCommand::boldCoord);
    }

    /**
     * The root project coordinate in bold — {@code group:artifact:version}, each
     * segment bold and in its usual {@link Coords} color. Bold must be baked into
     * each segment's style (a wrapping bold escape is cancelled by every segment's
     * color reset). Input is the plain {@code group:artifact:version}.
     */
    private static String boldCoord(String gav) {
        String[] p = gav.split(":", 3);
        if (p.length < 3) return Theme.colorize(gav, Coords.groupStyle().bold());
        return Theme.colorize(p[0], Coords.groupStyle().bold()) + ":"
                + Theme.colorize(p[1], Coords.artifactStyle().bold()) + ":"
                + Theme.colorize(p[2], Coords.versionStyle().bold());
    }
}
