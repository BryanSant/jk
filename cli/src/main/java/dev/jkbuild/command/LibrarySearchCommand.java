// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.library.LibraryCatalog;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.repo.RepoArtifactStore;
import dev.jkbuild.resolver.Versions;
import dev.jkbuild.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code jk library search <term>...} — substring match against the library catalog. */
public final class LibrarySearchCommand implements CliCommand {

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "Find library entries by substring of name, group, or artifact";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<N>", "Cap results shown (default: no cap)", "--limit"),
                Opt.flag("Append the source layer to each row.", "--show-layer"),
                Opt.flag("Group results under a heading per source layer.", "--group-by-layer"),
                Opt.value(
                                "<dir>",
                                "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "term",
                Arity.ONE_OR_MORE,
                "One or more substrings; all must match.\nMatched against name, group, or artifact."));
    }

    private boolean showLayer, groupByLayer;

    @Override
    public int run(Invocation in) {
        List<String> terms = in.positionals();
        Integer limit = in.value("limit").map(Integer::parseInt).orElse(null);
        this.showLayer = in.isSet("show-layer");
        this.groupByLayer = in.isSet("group-by-layer");
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);

        LibraryCatalog catalog = LibraryCatalog.layered(System.err::println);
        Path cacheRoot = cacheDir != null ? cacheDir : JkDirs.cache();
        List<String> lowerTerms =
                terms.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();

        List<Hit> hits = new ArrayList<>();
        for (String name : catalog.names()) {
            var src = catalog.source(name).orElseThrow();
            if (!allMatch(
                    lowerTerms,
                    name.toLowerCase(Locale.ROOT),
                    src.module().group().toLowerCase(Locale.ROOT),
                    src.module().artifact().toLowerCase(Locale.ROOT))) continue;
            List<String> cached = new ArrayList<>(
                    RepoArtifactStore.allVersions(cacheRoot, src.module().group(), src.module().artifact()));
            cached.sort((a, b) -> Versions.compare(b, a));
            if (global.offline && cached.isEmpty()) continue;
            hits.add(new Hit(name, src, cached));
        }

        if (hits.isEmpty()) {
            System.out.println(
                    "No matches" + (global.offline ? " (cached locally)" : "") + " for: " + String.join(" ", terms));
            return 1;
        }
        int total = hits.size();
        int shown = limit != null && limit > 0 && total > limit ? limit : total;
        List<Hit> visible = hits.subList(0, shown);
        int nameWidth = visible.stream().mapToInt(h -> h.name.length()).max().orElse(0);
        int leaderColumn = nameWidth + 2;

        if (groupByLayer) renderGrouped(catalog, visible, leaderColumn);
        else for (Hit h : visible) System.out.println(row(h, leaderColumn));
        if (shown < total)
            System.out.println("… and " + (total - shown) + " more (pass --limit " + total + " or refine the search)");
        return 0;
    }

    private void renderGrouped(LibraryCatalog catalog, List<Hit> visible, int leaderColumn) {
        boolean firstGroup = true;
        for (String layer : catalog.layerNames()) {
            List<Hit> inLayer =
                    visible.stream().filter(h -> h.src.layer().equals(layer)).toList();
            if (inLayer.isEmpty()) continue;
            if (!firstGroup) System.out.println();
            firstGroup = false;
            System.out.println(Theme.colorize(layer, Theme.active().cyan()));
            for (Hit h : inLayer) System.out.println(row(h, leaderColumn));
        }
    }

    private String row(Hit h, int leaderColumn) {
        String leader = ".".repeat(Math.max(2, leaderColumn - h.name.length()));
        String line = Coords.shortName(h.name)
                + Theme.colorize(leader, Theme.active().black())
                + Coords.module(h.src.module().moduleKey());
        if (showLayer && !groupByLayer)
            line += "  "
                    + Theme.colorize("[" + h.src.layer() + "]", Theme.active().cyan());
        if (!h.cached.isEmpty()) line += "  (cached: " + String.join(", ", h.cached) + ")";
        return line;
    }

    private static boolean allMatch(List<String> terms, String... fields) {
        for (String t : terms) {
            boolean found = false;
            for (String f : fields) {
                if (f.contains(t)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private record Hit(String name, LibraryCatalog.Source src, List<String> cached) {}
}
