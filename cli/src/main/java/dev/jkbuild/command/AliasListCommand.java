// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;

import dev.jkbuild.alias.AliasCatalog;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code jk alias list} — print every alias known to the layered
 * catalog along with the layer it resolves through. Useful when a
 * project-local override or downloaded entry is shadowing an unexpected
 * coordinate.
 */
@Command(name = "list", aliases = {"ls"},
        description = "List every alias the layered catalog resolves")
public final class AliasListCommand implements Callable<Integer> {

    @Option(names = "--layer",
            description = "Filter to a single source layer (project, local, global, bundled).")
    String layerFilter;

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
        int layerWidth = catalog.layerNames().stream().mapToInt(String::length).max().orElse(0);
        int shown = 0;
        for (String name : names) {
            var src = catalog.source(name).orElseThrow();
            if (layerFilter != null && !src.layer().equals(layerFilter)) continue;
            shown++;
            // Pad against the plain name width (color escapes have zero width).
            String namePad = " ".repeat(Math.max(0, nameWidth - name.length()));
            System.out.println(
                    Coords.shortName(name) + namePad + "  ["
                            + pad(src.layer(), layerWidth) + "]  "
                            + Coords.module(src.module().moduleKey()));
        }
        if (shown == 0 && layerFilter != null) {
            System.out.println("(no aliases in layer `" + layerFilter + "`)");
        }
        return 0;
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }
}
