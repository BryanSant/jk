// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.resolver.DependencyTree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

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
        return List.of(Opt.value("<depth>", "Maximum tree depth. Default: unlimited.", "--depth"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        Integer depth = in.value("depth").map(Integer::parseInt).orElse(null);
        Path dir = new GlobalOptions().workingDir();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk tree: no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(dir));
            return 2;
        }
        if (!Files.exists(lockFile)) {
            System.err.println("jk tree: no jk.lock in " + dev.jkbuild.cli.PathDisplay.styledRaw(dir) + " (run `jk lock` first)");
            return 2;
        }

        JkBuild project = JkBuildParser.parse(buildFile);
        Lockfile lock = LockfileReader.read(lockFile);
        int max = depth != null ? depth : Integer.MAX_VALUE;

        // Header: a leading blank line, then a left-flush green powerline chip
        // " - Dependencies Tree " (black on green, capped by a green ▶ segment arrow
        // when nerdfont) — the same chip family as jk build/idea. The project coord
        // moves to the root node line below.
        boolean nerdfont = dev.jkbuild.config.GlobalConfig.nerdfont();
        Theme t = Theme.active();
        String title = " - Dependencies Tree ";
        String header = nerdfont
                ? Theme.colorize(title, t.goalSuccessChip())
                        + Theme.colorize(dev.jkbuild.cli.tui.Glyphs.SEGMENT_END_NERD,
                                t.bright(t.goalChipColor()))
                : Theme.colorize(title, t.goalSuccessChip());
        System.out.println();
        System.out.println(header);
        // Composite-aware: walks path deps' own trees too (anchored at `dir`).
        String rendered = DependencyTree.render(project, lock, dir, max, styling(nerdfont));
        System.out.print(indentBody(rendered));
        if (rendered.contains(DependencyTree.MISSING_SUFFIX)) {
            System.out.println();
            System.out.println("Some dependencies are missing from your local cache. Run "
                    + Theme.colorize("jk lock", Theme.active().warning()));
        }
        return 0;
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
                scopeBadge);
    }
}
