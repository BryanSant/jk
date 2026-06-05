// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;

import dev.jkbuild.cache.Journal;
import dev.jkbuild.library.LibraryCatalog;
import dev.jkbuild.resolver.Versions;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code jk library search <term>...} — substring match against name,
 * group, and artifact across every layer of the library catalog. Multiple
 * terms are ANDed (each must appear somewhere in name/group/artifact).
 * Matches are case-insensitive.
 *
 * <p>Output shape mirrors {@code jk library list}: {@code name....group:artifact},
 * the dotted leader tracking each library across to its coordinate. The source
 * layer is hidden by default; surface it inline with {@code --show-layer} or as
 * section headings with {@code --group-by-layer} to see when a project- or
 * user-level override is shadowing the bundled coord.
 */
@Command(name = "search",
        description = "Find library entries by substring of name, group, or artifact")
public final class LibrarySearchCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "<term>",
            description = "One or more substrings. All must match (in name, group, or artifact).")
    List<String> terms;

    @Option(names = "--limit",
            description = "Cap the number of results displayed (default: no cap).")
    Integer limit;

    @Option(names = "--show-layer",
            description = "Append the source layer (project, local, global, bundled) to each row.")
    boolean showLayer;

    @Option(names = "--group-by-layer",
            description = "Group results under a heading per source layer, highest precedence first.")
    boolean groupByLayer;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDir;

    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
        LibraryCatalog catalog = LibraryCatalog.layered(System.err::println);
        Journal journal = new Journal(cacheDir != null ? cacheDir : JkDirs.cache());
        List<String> lowerTerms = new ArrayList<>(terms.size());
        for (String t : terms) lowerTerms.add(t.toLowerCase(Locale.ROOT));

        List<Hit> hits = new ArrayList<>();
        for (String name : catalog.names()) {
            var src = catalog.source(name).orElseThrow();
            String lowerName = name.toLowerCase(Locale.ROOT);
            String lowerGroup = src.module().group().toLowerCase(Locale.ROOT);
            String lowerArtifact = src.module().artifact().toLowerCase(Locale.ROOT);
            if (!allMatch(lowerTerms, lowerName, lowerGroup, lowerArtifact)) continue;
            // Annotate with what's actually cached locally; offline keeps only
            // coords we can use without a network.
            List<String> cached = new ArrayList<>(
                    journal.versions(src.module().group(), src.module().artifact()));
            cached.sort((a, b) -> Versions.compare(b, a)); // newest first
            if (global.offline && cached.isEmpty()) continue;
            hits.add(new Hit(name, src, cached));
        }

        if (hits.isEmpty()) {
            String suffix = global.offline ? " (cached locally)" : "";
            System.out.println("No matches" + suffix + " for: " + String.join(" ", terms));
            return 1;
        }

        int total = hits.size();
        int shown = limit != null && limit > 0 && total > limit ? limit : total;
        List<Hit> visible = hits.subList(0, shown);
        // Coordinates align at a fixed column filled with a dotted leader (see
        // jk library list); the column is computed over every visible hit so it
        // lines up across layer groups too. The longest name keeps a two-dot minimum.
        int nameWidth = visible.stream().mapToInt(h -> h.name.length()).max().orElse(0);
        int leaderColumn = nameWidth + 2;

        if (groupByLayer) {
            renderGrouped(catalog, visible, leaderColumn);
        } else {
            for (Hit h : visible) System.out.println(row(h, leaderColumn));
        }

        if (shown < total) {
            System.out.println("… and " + (total - shown) + " more "
                    + "(pass --limit " + total + " or refine the search)");
        }
        return 0;
    }

    private void renderGrouped(LibraryCatalog catalog, List<Hit> visible, int leaderColumn) {
        boolean firstGroup = true;
        for (String layer : catalog.layerNames()) {
            List<Hit> inLayer = new ArrayList<>();
            for (Hit h : visible) {
                if (h.src.layer().equals(layer)) inLayer.add(h);
            }
            if (inLayer.isEmpty()) continue;
            if (!firstGroup) System.out.println();
            firstGroup = false;
            System.out.println(Theme.colorize(layer, Theme.active().cyan()));
            for (Hit h : inLayer) System.out.println(row(h, leaderColumn));
        }
    }

    /** A single hit row: name, dotted leader, coordinate, optional layer tag, and any cached versions. */
    private String row(Hit h, int leaderColumn) {
        // Pad against the plain name width (color escapes have zero width).
        String leader = ".".repeat(Math.max(2, leaderColumn - h.name.length()));
        String line = Coords.shortName(h.name)
                + Theme.colorize(leader, Theme.active().black())
                + Coords.module(h.src.module().moduleKey());
        // When grouping, the heading already names the layer, so an inline tag
        // would just be noise — only append it in the flat listing.
        if (showLayer && !groupByLayer) {
            line += "  " + Theme.colorize("[" + h.src.layer() + "]", Theme.active().cyan());
        }
        if (!h.cached.isEmpty()) {
            line += "  (cached: " + String.join(", ", h.cached) + ")";
        }
        return line;
    }

    private static boolean allMatch(List<String> terms, String... fields) {
        for (String t : terms) {
            boolean found = false;
            for (String f : fields) {
                if (f.contains(t)) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private record Hit(String name, LibraryCatalog.Source src, List<String> cached) {}
}
