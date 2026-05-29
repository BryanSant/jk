// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.registry.AliasRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code jk registry list} — print every alias known to the layered
 * registry along with the layer it resolves through. Useful when a
 * project-local override or downloaded entry is shadowing an unexpected
 * coordinate.
 */
@Command(name = "list", aliases = {"ls"},
        description = "List every alias the layered registry resolves")
public final class RegistryListCommand implements Callable<Integer> {

    @Option(names = "--layer",
            description = "Filter to a single source layer (project, user, downloaded, bundled).")
    String layerFilter;

    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
        AliasRegistry registry = AliasRegistry.layered();
        var names = registry.names();
        if (names.isEmpty()) {
            System.out.println("(no aliases registered)");
            return 0;
        }
        int nameWidth = names.stream().mapToInt(String::length).max().orElse(0);
        int layerWidth = registry.layerNames().stream().mapToInt(String::length).max().orElse(0);
        int shown = 0;
        for (String name : names) {
            var src = registry.source(name).orElseThrow();
            if (layerFilter != null && !src.layer().equals(layerFilter)) continue;
            shown++;
            System.out.println(
                    pad(name, nameWidth) + "  ["
                            + pad(src.layer(), layerWidth) + "]  "
                            + src.module().moduleKey());
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
