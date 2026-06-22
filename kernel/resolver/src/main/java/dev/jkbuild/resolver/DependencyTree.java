// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Renders a Cargo-style dependency tree from a {@link JkBuild} project and
 * its resolved {@link Lockfile}. Revisited subtrees are marked with an
 * up-arrow ({@code ⎋}) so diamond-shaped graphs don't explode the output —
 * the arrow points the reader back to the earlier expansion.
 */
public final class DependencyTree {

    /**
     * Suffix appended to a node whose module has no resolved {@link Lockfile.Artifact}
     * — it is present in the graph but absent from the lockfile/local cache. Callers
     * can scan rendered output for this marker to decide whether to surface a hint.
     */
    public static final String MISSING_SUFFIX = " (missing)";

    /**
     * Optional ANSI styling for tree output. Each operator wraps the
     * matching piece of text with whatever escape sequence the caller
     * prefers. Defaults to {@link #plain()} (identity everywhere) so
     * tests and non-color consumers get raw ASCII back.
     *
     * <p>The fields map directly to the rendered shape:
     * <pre>
     *   {rail}└─ {/rail}{group}{group}{/group}:{artifact}{artifact}{/artifact}:{version}{version}{/version}
     * </pre>
     *
     * <p>{@code reference} styles a whole already-shown row (the {@code ⎋}
     * back-reference lines): the connector, coordinate, and marker are dimmed as
     * one unit so the reader sees at a glance it's a pointer to an earlier
     * expansion, not a fresh node. {@code scopeBadge} styles a scope section
     * header (the {@code Main} / {@code Test} / … badges grouping a project's
     * direct dependencies).
     */
    public record Styling(
            UnaryOperator<String> rail,
            UnaryOperator<String> group,
            UnaryOperator<String> artifact,
            UnaryOperator<String> version,
            UnaryOperator<String> reference,
            UnaryOperator<String> scopeBadge) {
        public static Styling plain() {
            return new Styling(UnaryOperator.identity(), UnaryOperator.identity(),
                    UnaryOperator.identity(), UnaryOperator.identity(),
                    UnaryOperator.identity(), UnaryOperator.identity());
        }
    }

    private DependencyTree() {}

    public static String render(JkBuild project, Lockfile lock) {
        return render(project, lock, Integer.MAX_VALUE);
    }

    public static String render(JkBuild project, Lockfile lock, int maxDepth) {
        return render(project, lock, maxDepth, Styling.plain());
    }

    /** Back-compat overload — rail-only styling. */
    public static String render(JkBuild project, Lockfile lock, int maxDepth,
                                UnaryOperator<String> railStyler) {
        return render(project, lock, maxDepth, new Styling(
                railStyler, UnaryOperator.identity(),
                UnaryOperator.identity(), UnaryOperator.identity(),
                UnaryOperator.identity(), UnaryOperator.identity()));
    }

    /**
     * Render with full styling. Labels are emitted in {@code group:artifact:version}
     * shape (the Maven coordinate convention Java developers expect), with each
     * segment passed through its corresponding {@link Styling} operator. Rail
     * connectors ({@code ├─ }, {@code └─ }, {@code │  }, {@code "   "}) are
     * passed through {@link Styling#rail}.
     */
    public static String render(JkBuild project, Lockfile lock, int maxDepth,
                                Styling styling) {
        Map<String, Lockfile.Artifact> byModule = indexByModule(lock);
        StringBuilder out = new StringBuilder();
        // Root project: group:artifact:version, styled like every other line.
        out.append(formatCoord(
                project.project().group(),
                project.project().name(),
                project.project().version(),
                styling)).append('\n');

        List<String> roots = collectRoots(project);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < roots.size(); i++) {
            renderNode(byModule, roots.get(i), 0, maxDepth,
                    i == roots.size() - 1, "", styling, seen, out);
        }
        return out.toString();
    }

    /**
     * Composite-aware render: walks the full graph including {@code path} (and
     * <em>branch</em> git) dependencies, not just lockfile Maven coords.
     * {@code projectDir} anchors {@code path} deps; each path target's own tree
     * (its {@code jk.toml} + {@code jk.lock}) is recursed into. Branch git deps
     * are annotated but not recursed (resolving them needs a clone). Immutable
     * (tag/rev) git deps are materialized into the lock and render normally.
     *
     * <p>When {@code project} is a <em>workspace root</em>, the scope sections
     * ({@code main}/{@code test}/…) are the top-level nodes. Under each scope sit
     * the workspace modules that declare at least one dependency in that scope, and
     * each such module node expands into its own deps for that scope (read from the
     * module's {@code jk.toml} + {@code jk.lock}). Workspace-sibling deps (a module's
     * {@code <name>.workspace = true} entries, which point at another module) are
     * shown as a collapsed {@code [workspace]} reference rather than re-expanded.
     */
    public static String render(JkBuild project, Lockfile lock, Path projectDir,
                                int maxDepth, Styling styling) {
        return render(project, lock, projectDir, maxDepth, styling, false);
    }

    /**
     * Composite-aware render with an optional {@code flatten} mode. When
     * {@code flatten} is set, each scope section lists the deduplicated, sorted
     * set of all (transitive) dependencies it pulls in as a flat list rather than
     * a nested tree — useful for "what's the full {@code main}/{@code test}/…
     * classpath?". {@code maxDepth} is ignored when flattening (the closure is
     * always walked in full).
     */
    public static String render(JkBuild project, Lockfile lock, Path projectDir,
                                int maxDepth, Styling styling, boolean flatten) {
        StringBuilder out = new StringBuilder();
        // Root node: a bright-black ● bullet, then the project's group:artifact:version.
        out.append(' ').append(styling.rail().apply("●")).append(' ')
                .append(formatCoord(project.project().group(), project.project().name(),
                        project.project().version(), styling)).append('\n');
        Set<String> seenModules = new HashSet<>();
        Set<String> seenDirs = new HashSet<>();
        if (project.isWorkspaceRoot()) {
            if (flatten) {
                renderFlatWorkspaceScopes(project, projectDir, styling, out);
            } else {
                renderWorkspaceScopes(project, projectDir, maxDepth, styling,
                        seenModules, seenDirs, out);
            }
        } else if (flatten) {
            renderFlatScopes(project, lock, projectDir, styling, out);
        } else {
            renderScopeSections(project, lock, projectDir, 0, maxDepth, "", styling,
                    Map.of(), seenModules, seenDirs, out);
        }
        return out.toString();
    }

    /**
     * Map of each workspace module's short name → its full {@code group:artifact}
     * coord, used to resolve a sibling's {@code workspace:<name>} dep reference back
     * to a readable coordinate when collapsing it in a module's subtree.
     */
    private static Map<String, String> workspaceModulesByName(List<String> modules, Path rootDir) {
        Map<String, String> byName = new HashMap<>();
        if (rootDir == null) return byName;
        for (String m : modules) {
            try {
                JkBuild b = JkBuildParser.parse(rootDir.resolve(m).normalize().resolve("jk.toml"));
                byName.put(b.project().name(), b.project().group() + ":" + b.project().name());
            } catch (Exception ignored) {
                // unreadable module jk.toml — sibling refs to it fall back to the raw module
            }
        }
        return byName;
    }

    /** A workspace module loaded for the scope-first view (build is required; lock may be absent). */
    private record LoadedModule(JkBuild build, Lockfile lock, Path dir) {}

    /**
     * Workspace-root view: scope sections (main/test/…) are the top-level nodes.
     * Under each scope sit the workspace modules that declare at least one
     * dependency in that scope, in declaration (build) order; each module node
     * expands into its own deps for that scope, with sibling modules collapsed to
     * a {@code [workspace]} reference.
     */
    private static void renderWorkspaceScopes(
            JkBuild root, Path rootDir, int maxDepth, Styling styling,
            Set<String> seenModules, Set<String> seenDirs, StringBuilder out) {

        List<String> moduleRels = root.workspace().modules();
        Map<String, String> byName = workspaceModulesByName(moduleRels, rootDir);
        List<LoadedModule> modules = loadModules(moduleRels, rootDir);

        // Scope sections present anywhere in the workspace, in display order.
        List<Scope> sections = new ArrayList<>();
        for (Scope s : SCOPE_SECTIONS) {
            if (modules.stream().anyMatch(m -> !m.build().dependencies().of(s).isEmpty())) {
                sections.add(s);
            }
        }

        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            boolean lastScope = si == sections.size() - 1;
            out.append(styling.rail().apply(lastScope ? "╰─" : "├─"))
                    .append(styling.scopeBadge().apply(scopeLabel(s))).append('\n');
            String scopePrefix = styling.rail().apply(lastScope ? "   " : "│  ");

            List<LoadedModule> inScope = modules.stream()
                    .filter(m -> !m.build().dependencies().of(s).isEmpty()).toList();
            for (int mi = 0; mi < inScope.size(); mi++) {
                LoadedModule m = inScope.get(mi);
                boolean lastMod = mi == inScope.size() - 1;
                String label = formatCoord(m.build().project().group(),
                        m.build().project().name(), m.build().project().version(), styling);
                String tag = m.lock() == null ? " [not locked]" : "";
                out.append(scopePrefix).append(styling.rail().apply(lastMod ? "╰─ " : "├─ "))
                        .append(label).append(styling.rail().apply(tag)).append('\n');
                String modPrefix = scopePrefix + styling.rail().apply(lastMod ? "   " : "│  ");
                renderScopeDepList(m.build(), m.lock(), m.dir(), s, 1, maxDepth, modPrefix,
                        styling, byName, seenModules, seenDirs, out);
            }
        }
    }

    /** Scopes shown as sections, in display order; only non-empty ones render. */
    private static final Scope[] SCOPE_SECTIONS = {
        Scope.MAIN, Scope.TEST, Scope.PROVIDED, Scope.RUNTIME,
        Scope.EXPORT, Scope.PROCESSOR, Scope.PLATFORM,
    };

    /**
     * Single-project view: scope sections (Main, Test, …) are nodes, each listing
     * its direct dependencies. A scope section only appears when it has ≥1 dep.
     */
    private static void renderScopeSections(
            JkBuild project, Lockfile lock, Path dir, int depth, int maxDepth,
            String prefix, Styling styling, Map<String, String> modules,
            Set<String> seenModules, Set<String> seenDirs, StringBuilder out) {

        List<Scope> sections = new ArrayList<>();
        for (Scope s : SCOPE_SECTIONS) {
            if (!project.dependencies().of(s).isEmpty()) sections.add(s);
        }
        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            boolean lastScope = si == sections.size() - 1;
            // Scope header: ├─/╰─ then the badge (no trailing space — badge abuts).
            out.append(prefix).append(styling.rail().apply(lastScope ? "╰─" : "├─"))
                    .append(styling.scopeBadge().apply(scopeLabel(s))).append('\n');
            // 4-wide continuation (matching a standard tree node) so the deps nest a
            // space further in than the 3-char scope connector — aligning under the badge.
            String scopePrefix = prefix + styling.rail().apply(lastScope ? "   " : "│  ");
            renderScopeDepList(project, lock, dir, s, depth, maxDepth, scopePrefix,
                    styling, modules, seenModules, seenDirs, out);
        }
    }

    /**
     * Render {@code project}'s direct dependencies in one {@code scope} under
     * {@code prefix}, each expanded transitively via {@code lock}. Shared by the
     * single-project view (deps under a scope header) and the workspace view (deps
     * under a module node).
     */
    private static void renderScopeDepList(
            JkBuild project, Lockfile lock, Path dir, Scope scope, int depth, int maxDepth,
            String prefix, Styling styling, Map<String, String> modules,
            Set<String> seenModules, Set<String> seenDirs, StringBuilder out) {

        Map<String, Lockfile.Artifact> byModule = lock == null ? Map.of() : indexByModule(lock);
        Map<String, Dependency> composite = compositeDeps(project);
        List<String> mods = project.dependencies().of(scope).stream()
                .map(Dependency::module).distinct().sorted().toList();
        for (int di = 0; di < mods.size(); di++) {
            renderDep(mods.get(di), composite, byModule, dir, depth, maxDepth,
                    di == mods.size() - 1, prefix, styling, modules, seenModules, seenDirs, out);
        }
    }

    /** Path + branch-git deps of a project, keyed by module, for {@link #renderDep} dispatch. */
    private static Map<String, Dependency> compositeDeps(JkBuild project) {
        Map<String, Dependency> composite = new HashMap<>();
        for (Scope s : Scope.values()) {
            for (Dependency d : project.dependencies().of(s)) {
                if (d.isPath() || (d.isGit() && d.gitSource().ref() instanceof GitRefSpec.Branch)) {
                    composite.putIfAbsent(d.module(), d);
                }
            }
        }
        return composite;
    }

    /** Load each workspace module (declaration order); a module whose jk.toml can't be parsed is dropped. */
    private static List<LoadedModule> loadModules(List<String> moduleRels, Path rootDir) {
        List<LoadedModule> modules = new ArrayList<>();
        for (String rel : moduleRels) {
            Path dir = rootDir == null ? null : rootDir.resolve(rel).normalize();
            JkBuild build = null;
            Lockfile lock = null;
            try {
                Path toml = dir == null ? null : dir.resolve("jk.toml");
                Path lf = dir == null ? null : dir.resolve("jk.lock");
                if (toml != null && Files.isRegularFile(toml)) build = JkBuildParser.parse(toml);
                if (lf != null && Files.isRegularFile(lf)) lock = LockfileReader.read(lf);
            } catch (Exception ignored) {
                // unreadable module — dropped (can't read its scopes)
            }
            if (build != null) modules.add(new LoadedModule(build, lock, dir));
        }
        return modules;
    }

    // --- flatten mode --------------------------------------------------------

    /** One flattened dependency: {@code group:artifact}, optional resolved version, and a tag. */
    private record FlatDep(String module, String version, String tag) {}

    /** Single-project flatten: each scope lists its full transitive dep closure, flat + sorted. */
    private static void renderFlatScopes(
            JkBuild project, Lockfile lock, Path dir, Styling styling, StringBuilder out) {

        Map<String, Lockfile.Artifact> byModule = lock == null ? Map.of() : indexByModule(lock);
        Map<String, Dependency> composite = compositeDeps(project);
        List<Scope> sections = new ArrayList<>();
        for (Scope s : SCOPE_SECTIONS) {
            if (!project.dependencies().of(s).isEmpty()) sections.add(s);
        }
        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            Map<String, FlatDep> collected = new TreeMap<>();
            Set<String> visited = new HashSet<>();
            for (String m : directModules(project, s)) {
                collectFlat(m, composite, byModule, Map.of(), visited, collected);
            }
            renderFlatSection(s, si == sections.size() - 1, collected, "", styling, out);
        }
    }

    /** Workspace-root flatten: each scope is the union of every module's closure for that scope. */
    private static void renderFlatWorkspaceScopes(
            JkBuild root, Path rootDir, Styling styling, StringBuilder out) {

        Map<String, String> byName = workspaceModulesByName(root.workspace().modules(), rootDir);
        List<LoadedModule> modules = loadModules(root.workspace().modules(), rootDir);

        List<Scope> sections = new ArrayList<>();
        for (Scope s : SCOPE_SECTIONS) {
            if (modules.stream().anyMatch(m -> !m.build().dependencies().of(s).isEmpty())) {
                sections.add(s);
            }
        }
        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            Map<String, FlatDep> collected = new TreeMap<>();
            Set<String> visited = new HashSet<>();
            for (LoadedModule m : modules) {
                if (m.build().dependencies().of(s).isEmpty()) continue;
                Map<String, Lockfile.Artifact> byModule =
                        m.lock() == null ? Map.of() : indexByModule(m.lock());
                Map<String, Dependency> composite = compositeDeps(m.build());
                for (String dep : directModules(m.build(), s)) {
                    collectFlat(dep, composite, byModule, byName, visited, collected);
                }
            }
            renderFlatSection(s, si == sections.size() - 1, collected, "", styling, out);
        }
    }

    /** Emit a scope header followed by its flattened, sorted dependency list. */
    private static void renderFlatSection(
            Scope scope, boolean lastScope, Map<String, FlatDep> collected,
            String prefix, Styling styling, StringBuilder out) {

        out.append(prefix).append(styling.rail().apply(lastScope ? "╰─" : "├─"))
                .append(styling.scopeBadge().apply(scopeLabel(scope))).append('\n');
        String scopePrefix = prefix + styling.rail().apply(lastScope ? "   " : "│  ");
        List<FlatDep> deps = new ArrayList<>(collected.values());
        for (int i = 0; i < deps.size(); i++) {
            FlatDep d = deps.get(i);
            out.append(scopePrefix).append(styling.rail().apply(i == deps.size() - 1 ? "╰─ " : "├─ "))
                    .append(coordVersioned(d.module(), d.version(), styling))
                    .append(styling.rail().apply(d.tag())).append('\n');
        }
    }

    /** Walk a dependency and its transitive closure, accumulating distinct coords into {@code out}. */
    private static void collectFlat(
            String module, Map<String, Dependency> composite,
            Map<String, Lockfile.Artifact> byModule, Map<String, String> byName,
            Set<String> visited, Map<String, FlatDep> out) {

        if (module.startsWith("workspace:")) {
            String name = module.substring("workspace:".length());
            putFlat(out, new FlatDep(byName.getOrDefault(name, module), null, " [workspace]"));
            return;
        }
        Dependency comp = composite.get(module);
        if (comp != null && comp.isPath()) {
            putFlat(out, new FlatDep(module, null, " [path]"));
            return;
        }
        if (comp != null && comp.isGit()) {
            String ref = ((GitRefSpec.Branch) comp.gitSource().ref()).name();
            putFlat(out, new FlatDep(module, null, " [git: " + ref + "]"));
            return;
        }
        if (!visited.add(module)) return;
        Lockfile.Artifact pkg = byModule.get(module);
        if (pkg == null) {
            putFlat(out, new FlatDep(module, null, MISSING_SUFFIX));
            return;
        }
        putFlat(out, new FlatDep(module, pkg.version(), ""));
        for (String child : pkg.deps()) {
            collectFlat(stripVersion(child), composite, byModule, byName, visited, out);
        }
    }

    /** Dedup by {@code group:artifact}, preferring an entry that carries a resolved version. */
    private static void putFlat(Map<String, FlatDep> out, FlatDep dep) {
        FlatDep existing = out.get(dep.module());
        if (existing == null || (existing.version() == null && dep.version() != null)) {
            out.put(dep.module(), dep);
        }
    }

    /** A project's direct dep modules in one scope, distinct + sorted. */
    private static List<String> directModules(JkBuild project, Scope scope) {
        return project.dependencies().of(scope).stream()
                .map(Dependency::module).distinct().sorted().toList();
    }

    /** {@code group:artifact:version} when a version is known, else {@code group:artifact}. */
    private static String coordVersioned(String module, String version, Styling styling) {
        int colon = module.indexOf(':');
        String groupId = colon > 0 ? module.substring(0, colon) : module;
        String artifactId = colon > 0 ? module.substring(colon + 1) : "";
        return version == null
                ? styling.group().apply(groupId) + ":" + styling.artifact().apply(artifactId)
                : formatCoord(groupId, artifactId, version, styling);
    }

    /** Render one direct dependency, dispatching on its kind (maven / workspace / path / branch-git). */
    private static void renderDep(
            String module, Map<String, Dependency> composite, Map<String, Lockfile.Artifact> byModule,
            Path dir, int depth, int maxDepth, boolean isLast, String prefix, Styling styling,
            Map<String, String> modules, Set<String> seenModules, Set<String> seenDirs, StringBuilder out) {

        if (module.startsWith("workspace:")) {
            // Workspace sibling (a `<name>.workspace = true` dep) — already shown
            // at the top level, and its external deps aren't in this module's lock.
            // Resolve the synthetic "workspace:<name>" back to a real coord and
            // reference it (no recursion).
            String name = module.substring("workspace:".length());
            String coord = modules.getOrDefault(name, module);
            out.append(prefix).append(styling.rail().apply(isLast ? "╰─ " : "├─ "))
                    .append(coordLabel(coord, styling))
                    .append(styling.rail().apply(" [workspace]")).append('\n');
            return;
        }
        Dependency comp = composite.get(module);
        if (comp == null) {
            renderNode(byModule, module, depth, maxDepth, isLast, prefix, styling, seenModules, out);
        } else if (comp.isPath()) {
            renderPathNode(comp, module, dir, depth, maxDepth, isLast, prefix, styling,
                    modules, seenModules, seenDirs, out);
        } else {
            // branch git dep — annotate (no recursion; needs a clone).
            String ref = ((GitRefSpec.Branch) comp.gitSource().ref()).name();
            out.append(prefix).append(styling.rail().apply(isLast ? "╰─ " : "├─ "))
                    .append(coordLabel(module, styling))
                    .append(styling.rail().apply(" [git: " + ref + "]")).append('\n');
        }
    }

    /** The bare lowercase scope name for a section badge: {@code MAIN} → {@code "main"}.
     *  The {@link Styling#scopeBadge} styler decides padding vs. pill caps. */
    private static String scopeLabel(Scope s) {
        return s.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** A path dep node, recursing into the target's own tree (its jk.toml + jk.lock). */
    private static void renderPathNode(
            Dependency dep, String module, Path consumerDir, int depth, int maxDepth,
            boolean isLast, String prefix, Styling styling, Map<String, String> modules,
            Set<String> seenModules, Set<String> seenDirs, StringBuilder out) {

        String connector = isLast ? "╰─ " : "├─ ";
        Path targetDir = consumerDir == null ? null : consumerDir.resolve(dep.pathSource()).normalize();
        boolean cycle = targetDir != null && !seenDirs.add(targetDir.toString());
        Path lockPath = targetDir == null ? null : targetDir.resolve("jk.lock");
        boolean built = lockPath != null && Files.isRegularFile(lockPath);
        String tag = !built ? " [path, not built]" : " [path]";

        if (cycle) {
            // Back-reference to a path dir already expanded — dim the whole row.
            out.append(prefix).append(styling.reference().apply(connector + module + tag + " ⎋")).append('\n');
            return;
        }

        out.append(prefix).append(styling.rail().apply(connector))
                .append(coordLabel(module, styling))
                .append(styling.rail().apply(tag)).append('\n');

        if (targetDir == null || !built || depth >= maxDepth) return;
        JkBuild target;
        Lockfile targetLock;
        try {
            target = JkBuildParser.parse(targetDir.resolve("jk.toml"));
            targetLock = LockfileReader.read(lockPath);
        } catch (Exception e) {
            return; // unreadable target — stop descending
        }
        String childPrefix = prefix + styling.rail().apply(isLast ? "   " : "│  ");
        renderScopeSections(target, targetLock, targetDir, depth + 1, maxDepth, childPrefix,
                styling, modules, seenModules, seenDirs, out);
    }

    /** {@code group:artifact} styled (no version — composite deps carry none here). */
    private static String coordLabel(String module, Styling styling) {
        int colon = module.indexOf(':');
        String groupId = colon > 0 ? module.substring(0, colon) : module;
        String artifactId = colon > 0 ? module.substring(colon + 1) : "";
        return styling.group().apply(groupId) + ":" + styling.artifact().apply(artifactId);
    }

    private static void renderNode(
            Map<String, Lockfile.Artifact> byModule,
            String module,
            int depth,
            int maxDepth,
            boolean isLast,
            String prefix,
            Styling styling,
            Set<String> seen,
            StringBuilder out) {

        Lockfile.Artifact pkg = byModule.get(module);
        // module is "group:artifact"; split for per-segment styling.
        int colon = module.indexOf(':');
        String groupId = colon > 0 ? module.substring(0, colon) : module;
        String artifactId = colon > 0 ? module.substring(colon + 1) : "";

        // ╰─ for the last child (rounded arc); ├─ for the rest.
        // Standard "rounded tree" convention used by eza, tre, etc.
        String connector = isLast ? "╰─ " : "├─ ";
        String coord = pkg != null
                ? groupId + ":" + artifactId + ":" + pkg.version()
                : groupId + ":" + artifactId + MISSING_SUFFIX;

        if (!seen.add(module)) {
            // Already shown higher up — dim the WHOLE row (connector + coord + ⎋)
            // so it reads as a back-reference, not a fresh expansion.
            out.append(prefix).append(styling.reference().apply(connector + coord + " ⎋")).append('\n');
            return;
        }

        String label = pkg != null
                ? formatCoord(groupId, artifactId, pkg.version(), styling)
                // No version available — "group:artifact (missing)", marker unstyled.
                : styling.group().apply(groupId) + ":"
                        + styling.artifact().apply(artifactId) + MISSING_SUFFIX;

        out.append(prefix).append(styling.rail().apply(connector))
                .append(label).append('\n');

        if (pkg == null || depth >= maxDepth) return;

        String childPrefix = prefix + styling.rail().apply(isLast ? "   " : "│  ");
        List<String> children = pkg.deps().stream()
                .map(DependencyTree::stripVersion)
                .sorted()
                .toList();
        for (int i = 0; i < children.size(); i++) {
            renderNode(byModule, children.get(i), depth + 1, maxDepth,
                    i == children.size() - 1, childPrefix, styling, seen, out);
        }
    }

    private static String formatCoord(String group, String artifact, String version,
                                      Styling styling) {
        return styling.group().apply(group)
                + ":" + styling.artifact().apply(artifact)
                + ":" + styling.version().apply(version);
    }

    static Map<String, Lockfile.Artifact> indexByModule(Lockfile lock) {
        Map<String, Lockfile.Artifact> result = new HashMap<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            result.put(pkg.name(), pkg);
        }
        return result;
    }

    static List<String> collectRoots(JkBuild project) {
        return Stream.of(Scope.values())
                .flatMap(s -> project.dependencies().of(s).stream())
                .map(Dependency::module)
                .sorted()
                .distinct()
                .collect(ArrayList::new, List::add, List::addAll);
    }

    /** Strip the {@code @version} suffix from a lockfile dep ref. */
    static String stripVersion(String depRef) {
        int at = depRef.indexOf('@');
        return at > 0 ? depRef.substring(0, at) : depRef;
    }

    // Sorting helper exposed for tests that want a deterministic order.
    static Comparator<Lockfile.Artifact> byName() {
        return Comparator.comparing(Lockfile.Artifact::name);
    }
}
