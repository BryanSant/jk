// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.registry.AliasRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code jk registry search <term>...} — substring match against name,
 * group, and artifact across every layer of the alias registry. Multiple
 * terms are ANDed (each must appear somewhere in name/group/artifact).
 * Matches are case-insensitive.
 *
 * <p>Output shape mirrors {@code jk registry list}: {@code name [layer]
 * group:artifact}. The layer tag lets the user see when a project- or
 * user-level override is shadowing the bundled coord.
 */
@Command(name = "search",
        description = "Find registry entries by substring of name, group, or artifact")
public final class RegistrySearchCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "<term>",
            description = "One or more substrings. All must match (in name, group, or artifact).")
    List<String> terms;

    @Option(names = "--limit",
            description = "Cap the number of results displayed (default: no cap).")
    Integer limit;

    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
        AliasRegistry registry = AliasRegistry.layered();
        List<String> lowerTerms = new ArrayList<>(terms.size());
        for (String t : terms) lowerTerms.add(t.toLowerCase(Locale.ROOT));

        List<Hit> hits = new ArrayList<>();
        for (String name : registry.names()) {
            var src = registry.source(name).orElseThrow();
            String lowerName = name.toLowerCase(Locale.ROOT);
            String lowerGroup = src.module().group().toLowerCase(Locale.ROOT);
            String lowerArtifact = src.module().artifact().toLowerCase(Locale.ROOT);
            if (allMatch(lowerTerms, lowerName, lowerGroup, lowerArtifact)) {
                hits.add(new Hit(name, src));
            }
        }

        if (hits.isEmpty()) {
            System.out.println("No matches for: " + String.join(" ", terms));
            return 1;
        }

        int total = hits.size();
        int shown = limit != null && limit > 0 && total > limit ? limit : total;
        int nameWidth = 0;
        int layerWidth = 0;
        for (int i = 0; i < shown; i++) {
            Hit h = hits.get(i);
            nameWidth = Math.max(nameWidth, h.name.length());
            layerWidth = Math.max(layerWidth, h.src.layer().length());
        }

        for (int i = 0; i < shown; i++) {
            Hit h = hits.get(i);
            System.out.println(
                    pad(h.name, nameWidth) + "  ["
                            + pad(h.src.layer(), layerWidth) + "]  "
                            + h.src.module().moduleKey());
        }
        if (shown < total) {
            System.out.println("… and " + (total - shown) + " more "
                    + "(pass --limit " + total + " or refine the search)");
        }
        return 0;
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

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    private record Hit(String name, AliasRegistry.Source src) {}
}
