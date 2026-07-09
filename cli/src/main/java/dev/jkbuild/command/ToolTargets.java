// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.library.LibraryCatalog;
import dev.jkbuild.model.ToolCoordSpec;
import dev.jkbuild.tool.ToolTarget;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@code jk tool run|install} target into an engine-ready {@link ToolCoordSpec} string
 * (docs/tool-targets-plan.md §2, §4.1–4.2, §6.1). File targets never reach this class — the
 * commands route {@link ToolTarget.RunnableFile} to {@code ScriptRunner} first.
 *
 * <p>Bare names resolve against the layered library catalog (bundled → global → local); per §6.1
 * the catalog lookup IS the {@code name@suffix} disambiguation — a hit reads the suffix as a
 * floating version selector, a miss reports both interpretations. Target kinds that later phases
 * own (directories, URLs, git, JBang catalogs) fail with a pointer instead of a parse error.
 */
final class ToolTargets {

    /** A target resolved to wire shape: the coord spec to send and the launcher-name default. */
    record Resolved(String coordSpec, String defaultBin) {}

    /** User-facing classification/lookup failure; the message is ready to print. */
    static final class TargetException extends RuntimeException {
        TargetException(String message) {
            super(message);
        }
    }

    private ToolTargets() {}

    /** Resolve a non-file target. Throws {@link TargetException} with a rendered message. */
    static Resolved resolve(String target) {
        return switch (ToolTarget.classify(target)) {
            case ToolTarget.RunnableFile f ->
                throw new IllegalStateException("file targets are ScriptRunner's job: " + target);
            case ToolTarget.Gav g -> {
                ToolCoordSpec spec = parseSpec(g.raw());
                yield new Resolved(g.raw(), artifactOf(spec));
            }
            case ToolTarget.CatalogName c -> resolveCatalogName(c);
            case ToolTarget.JBangAlias j ->
                throw new TargetException("jk tool: JBang catalog aliases (`alias@user/repo`) aren't supported yet"
                        + " — planned in docs/tool-targets-plan.md §6.");
            case ToolTarget.Url u ->
                throw new TargetException(
                        "jk tool: web URL targets aren't supported yet — planned in docs/tool-targets-plan.md §4.5.");
            case ToolTarget.Git g ->
                throw new TargetException("jk tool: git targets aren't supported yet"
                        + " (docs/tool-targets-plan.md §4.6). To install an app from a git repo, use `jk install "
                        + g.raw() + "`.");
            case ToolTarget.Directory d ->
                throw new TargetException("jk tool: directory targets aren't supported yet"
                        + " (docs/tool-targets-plan.md §4.4). To run a jk project, `cd` into it and use `jk run`.");
            case ToolTarget.UnsupportedFile f ->
                throw new TargetException(
                        "jk tool: " + f.path() + " is not a runnable file (.java, .kt, .kts, or .jar).");
        };
    }

    /**
     * Resolve each {@code --with} value — a coord spec ({@code g:a[:v|@sel]}) or a catalog
     * short-name ({@code name[@sel]}) — to wire shape.
     */
    static List<String> resolveWith(List<String> with) {
        List<String> out = new ArrayList<>();
        for (String w : with) {
            if (w.contains(":")) {
                parseSpec(w); // validate client-side for an early, local error
                out.add(w);
            } else {
                out.add(resolve(w).coordSpec());
            }
        }
        return out;
    }

    private static Resolved resolveCatalogName(ToolTarget.CatalogName c) {
        LibraryCatalog catalog = LibraryCatalog.layered();
        var module = catalog.lookup(c.name()).orElseThrow(() -> catalogMiss(catalog, c));
        // Validate the suffix now (it rides the wire verbatim).
        if (c.suffix() != null) {
            try {
                dev.jkbuild.model.VersionSelector.parseFloating(c.suffix());
            } catch (IllegalArgumentException e) {
                throw new TargetException("jk tool: bad version selector `@" + c.suffix() + "` on `" + c.name()
                        + "`: " + e.getMessage());
            }
        }
        String coordSpec = module.group() + ":" + module.artifact() + (c.suffix() != null ? "@" + c.suffix() : "");
        // The catalog name is the natural launcher name (`ktlint`, not `ktlint-cli`).
        return new Resolved(coordSpec, c.name());
    }

    private static TargetException catalogMiss(LibraryCatalog catalog, ToolTarget.CatalogName c) {
        StringBuilder msg = new StringBuilder("jk tool: `" + c.name() + "` is not in the library catalog");
        if (c.suffix() != null) {
            // §6.1: a bare-word suffix is only ambiguous when the name resolves; on a miss,
            // report both interpretations and the forced spellings.
            msg.append(", and JBang catalogs aren't supported yet (docs/tool-targets-plan.md §6.1).\n")
                    .append("Use `group:artifact@")
                    .append(c.suffix())
                    .append("` for a version, or wait for `alias@user/repo` support.");
        } else {
            msg.append(".");
        }
        List<String> suggestions = catalog.suggestionsFor(c.name(), 3);
        if (!suggestions.isEmpty()) {
            msg.append("\nDid you mean: ").append(String.join(", ", suggestions)).append("?");
        }
        msg.append("\nUse a full coordinate (`group:artifact[:version]`) to skip the catalog.");
        return new TargetException(msg.toString());
    }

    private static ToolCoordSpec parseSpec(String raw) {
        try {
            return ToolCoordSpec.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new TargetException("jk tool: " + e.getMessage());
        }
    }

    private static String artifactOf(ToolCoordSpec spec) {
        return switch (spec) {
            case ToolCoordSpec.Pinned p -> p.coordinate().artifact();
            case ToolCoordSpec.Floating f -> f.artifact();
        };
    }
}
