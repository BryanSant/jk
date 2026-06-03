// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;

import dev.jkbuild.alias.AliasCatalog;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code jk alias list} — print every alias known to the layered catalog as
 * {@code name....group:artifact}, the dotted leader tracking each alias across
 * to its coordinate. The source layer each alias resolves through is hidden by
 * default; surface it inline with {@code --show-layer} or as section headings
 * with {@code --group-by-layer} when a project-local override or downloaded
 * entry is shadowing an unexpected coordinate.
 */
@Command(name = "list", aliases = {"ls"},
        description = "List every alias the layered catalog resolves")
public final class AliasListCommand implements Callable<Integer> {

    @Option(names = "--layer",
            description = "Filter to a single source layer (project, local, global, bundled).")
    String layerFilter;

    @Option(names = "--show-layer",
            description = "Append the source layer (project, local, global, bundled) to each row.")
    boolean showLayer;

    @Option(names = "--group-by-layer",
            description = "Group aliases under a heading per source layer, highest precedence first.")
    boolean groupByLayer;

    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
        AliasCatalog catalog = AliasCatalog.layered(System.err::println);
        var names = catalog.names();
        if (names.isEmpty()) {
            System.out.println("(no aliases registered)");
            return 0;
        }
        int nameWidth = names.stream().mapToInt(String::length).max().orElse(0);
        // Coordinates align at a fixed column; the gap is filled with a dotted
        // leader so the eye can track each alias across to its coordinate. The
        // longest name still gets a two-dot minimum. The column is computed over
        // every name so coordinates line up across layer groups too.
        int leaderColumn = nameWidth + 2;
        return groupByLayer
                ? listGrouped(catalog, names, leaderColumn)
                : listFlat(catalog, names, leaderColumn);
    }

    private Integer listFlat(AliasCatalog catalog, Set<String> names, int leaderColumn) {
        int shown = 0;
        for (String name : names) {
            var src = catalog.source(name).orElseThrow();
            if (layerFilter != null && !src.layer().equals(layerFilter)) continue;
            shown++;
            System.out.println(row(name, src, leaderColumn));
        }
        if (shown == 0 && layerFilter != null) {
            System.out.println("(no aliases in layer `" + layerFilter + "`)");
        }
        return 0;
    }

    private Integer listGrouped(AliasCatalog catalog, Set<String> names, int leaderColumn) {
        boolean firstGroup = true;
        int shown = 0;
        for (String layer : catalog.layerNames()) {
            if (layerFilter != null && !layer.equals(layerFilter)) continue;
            // Names resolving through this layer, in the catalog's lexicographic order.
            List<String> inLayer = new ArrayList<>();
            for (String name : names) {
                if (catalog.source(name).orElseThrow().layer().equals(layer)) inLayer.add(name);
            }
            if (inLayer.isEmpty()) continue;
            if (!firstGroup) System.out.println();
            firstGroup = false;
            System.out.println(Theme.colorize(layer, Theme.active().cyan()));
            for (String name : inLayer) {
                shown++;
                System.out.println(row(name, catalog.source(name).orElseThrow(), leaderColumn));
            }
        }
        if (shown == 0 && layerFilter != null) {
            System.out.println("(no aliases in layer `" + layerFilter + "`)");
        }
        return 0;
    }

    /** A single alias row: name, dotted leader, coordinate, and (with {@code --show-layer}) the layer tag. */
    private String row(String name, AliasCatalog.Source src, int leaderColumn) {
        // Pad against the plain name width (color escapes have zero width).
        String leader = ".".repeat(Math.max(2, leaderColumn - name.length()));
        String line = Coords.shortName(name)
                + Theme.colorize(leader, Theme.active().black())
                + Coords.module(src.module().moduleKey());
        // When grouping, the heading already names the layer, so an inline tag
        // would just be noise — only append it in the flat listing.
        if (showLayer && !groupByLayer) {
            line += "  " + Theme.colorize("[" + src.layer() + "]", Theme.active().cyan());
        }
        return line;
    }
}
