// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.library.LibraryCatalog;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code jk library list} — print every library known to the layered catalog.
 */
public final class LibraryListCommand implements CliCommand {

    @Override public String name() { return "list"; }
    @Override public String description() { return "List every library the layered catalog resolves"; }
    @Override public List<String> aliases() { return List.of("ls"); }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<layer>", "Filter to a single source layer (project, local, global, bundled).", "--layer"),
                Opt.flag("Append the source layer to each row.", "--show-layer"),
                Opt.flag("Group libraries under a heading per source layer.", "--group-by-layer"));
    }

    private String layerFilter;
    private boolean showLayer;
    private boolean groupByLayer;

    @Override
    public int run(Invocation in) {
        this.layerFilter = in.value("layer").orElse(null);
        this.showLayer = in.isSet("show-layer");
        this.groupByLayer = in.isSet("group-by-layer");

        LibraryCatalog catalog = LibraryCatalog.layered(System.err::println);
        Set<String> names = catalog.names();
        if (names.isEmpty()) { System.out.println("(no libraries registered)"); return 0; }
        int nameWidth = names.stream().mapToInt(String::length).max().orElse(0);
        int leaderColumn = nameWidth + 2;
        return groupByLayer ? listGrouped(catalog, names, leaderColumn) : listFlat(catalog, names, leaderColumn);
    }

    private int listFlat(LibraryCatalog catalog, Set<String> names, int leaderColumn) {
        int shown = 0;
        for (String name : names) {
            var src = catalog.source(name).orElseThrow();
            if (layerFilter != null && !src.layer().equals(layerFilter)) continue;
            shown++;
            System.out.println(row(name, src, leaderColumn));
        }
        if (shown == 0 && layerFilter != null) System.out.println("(no libraries in layer `" + layerFilter + "`)");
        return 0;
    }

    private int listGrouped(LibraryCatalog catalog, Set<String> names, int leaderColumn) {
        boolean firstGroup = true;
        int shown = 0;
        for (String layer : catalog.layerNames()) {
            if (layerFilter != null && !layer.equals(layerFilter)) continue;
            List<String> inLayer = new ArrayList<>();
            for (String name : names) {
                if (catalog.source(name).orElseThrow().layer().equals(layer)) inLayer.add(name);
            }
            if (inLayer.isEmpty()) continue;
            if (!firstGroup) System.out.println();
            firstGroup = false;
            System.out.println(Theme.colorize(layer, Theme.active().cyan()));
            for (String name : inLayer) { shown++; System.out.println(row(name, catalog.source(name).orElseThrow(), leaderColumn)); }
        }
        if (shown == 0 && layerFilter != null) System.out.println("(no libraries in layer `" + layerFilter + "`)");
        return 0;
    }

    private String row(String name, LibraryCatalog.Source src, int leaderColumn) {
        String leader = ".".repeat(Math.max(2, leaderColumn - name.length()));
        String line = Coords.shortName(name) + Theme.colorize(leader, Theme.active().black()) + Coords.module(src.module().moduleKey());
        if (showLayer && !groupByLayer) line += "  " + Theme.colorize("[" + src.layer() + "]", Theme.active().cyan());
        return line;
    }
}
