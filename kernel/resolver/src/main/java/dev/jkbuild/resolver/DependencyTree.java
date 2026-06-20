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
     *   {rail}└── {/rail}{group}{group}{/group}:{artifact}{artifact}{/artifact}:{version}{version}{/version}
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
     * connectors ({@code ├── }, {@code └── }, {@code │   }, {@code "    "}) are
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
     * <p>When {@code project} is a <em>workspace root</em>, every member is walked:
     * each member's coordinate is printed as a child of the root and its own
     * resolved tree (its {@code jk.toml} + {@code jk.lock}) is recursed into.
     * Workspace-sibling deps (a member's {@code <name>.workspace = true} entries,
     * which point at another member) are shown as a collapsed {@code [workspace]}
     * reference rather than re-expanded — each sibling already appears at the top
     * level.
     */
    public static String render(JkBuild project, Lockfile lock, Path projectDir,
                                int maxDepth, Styling styling) {
        StringBuilder out = new StringBuilder();
        out.append(formatCoord(project.project().group(), project.project().name(),
                project.project().version(), styling)).append('\n');
        Set<String> seenModules = new HashSet<>();
        Set<String> seenDirs = new HashSet<>();
        if (project.isWorkspaceRoot()) {
            List<String> members = project.workspace().members();
            Map<String, String> byName = workspaceMembersByName(members, projectDir);
            for (int i = 0; i < members.size(); i++) {
                renderWorkspaceMember(members.get(i), projectDir, 0, maxDepth,
                        i == members.size() - 1, "", styling, byName,
                        seenModules, seenDirs, out);
            }
        } else {
            renderComposite(project, lock, projectDir, 0, maxDepth, "", styling,
                    Map.of(), seenModules, seenDirs, out);
        }
        return out.toString();
    }

    /**
     * Map of each workspace member's short name → its full {@code group:artifact}
     * coord, used to resolve a sibling's {@code workspace:<name>} dep reference back
     * to a readable coordinate when collapsing it in a member's subtree.
     */
    private static Map<String, String> workspaceMembersByName(List<String> members, Path rootDir) {
        Map<String, String> byName = new HashMap<>();
        if (rootDir == null) return byName;
        for (String m : members) {
            try {
                JkBuild b = JkBuildParser.parse(rootDir.resolve(m).normalize().resolve("jk.toml"));
                byName.put(b.project().name(), b.project().group() + ":" + b.project().name());
            } catch (Exception ignored) {
                // unreadable member jk.toml — sibling refs to it fall back to the raw module
            }
        }
        return byName;
    }

    /** A workspace member node, recursing into the member's own tree (its jk.toml + jk.lock). */
    private static void renderWorkspaceMember(
            String memberRel, Path rootDir, int depth, int maxDepth, boolean isLast,
            String prefix, Styling styling, Map<String, String> members,
            Set<String> seenModules, Set<String> seenDirs, StringBuilder out) {

        String connector = isLast ? "╰── " : "├── ";
        Path memberDir = rootDir == null ? null : rootDir.resolve(memberRel).normalize();
        Path tomlPath = memberDir == null ? null : memberDir.resolve("jk.toml");
        Path lockPath = memberDir == null ? null : memberDir.resolve("jk.lock");

        JkBuild member = null;
        Lockfile memberLock = null;
        try {
            if (tomlPath != null && Files.isRegularFile(tomlPath)) member = JkBuildParser.parse(tomlPath);
            if (lockPath != null && Files.isRegularFile(lockPath)) memberLock = LockfileReader.read(lockPath);
        } catch (Exception ignored) {
            // unreadable member — fall through to the tag below
        }

        String label = member != null
                ? formatCoord(member.project().group(), member.project().name(),
                        member.project().version(), styling)
                : styling.artifact().apply(memberRel);
        String tag = member == null ? " [unreadable]" : (memberLock == null ? " [not locked]" : "");
        out.append(prefix).append(styling.rail().apply(connector))
                .append(label).append(styling.rail().apply(tag)).append('\n');

        if (member == null || memberLock == null || depth >= maxDepth) return;
        String childPrefix = prefix + styling.rail().apply(isLast ? "    " : "│   ");
        renderComposite(member, memberLock, memberDir, depth + 1, maxDepth, childPrefix,
                styling, members, seenModules, seenDirs, out);
    }

    /** Scopes shown as sections, in display order; only non-empty ones render. */
    private static final Scope[] SCOPE_SECTIONS = {
        Scope.MAIN, Scope.TEST, Scope.PROVIDED, Scope.RUNTIME,
        Scope.EXPORT, Scope.PROCESSOR, Scope.PLATFORM,
    };

    private static void renderComposite(
            JkBuild project, Lockfile lock, Path dir, int depth, int maxDepth,
            String prefix, Styling styling, Map<String, String> members,
            Set<String> seenModules, Set<String> seenDirs, StringBuilder out) {

        Map<String, Lockfile.Artifact> byModule = indexByModule(lock);
        Map<String, Dependency> composite = new HashMap<>();
        for (Scope s : Scope.values()) {
            for (Dependency d : project.dependencies().of(s)) {
                if (d.isPath() || (d.isGit() && d.gitSource().ref() instanceof GitRefSpec.Branch)) {
                    composite.putIfAbsent(d.module(), d);
                }
            }
        }

        // Split direct deps into scope sections (Main, Test, …); a scope section
        // only appears when it has at least one dependency.
        List<Scope> sections = new ArrayList<>();
        Map<Scope, List<String>> bySectionScope = new java.util.EnumMap<>(Scope.class);
        for (Scope s : SCOPE_SECTIONS) {
            List<String> mods = project.dependencies().of(s).stream()
                    .map(Dependency::module).distinct().sorted().toList();
            if (!mods.isEmpty()) {
                sections.add(s);
                bySectionScope.put(s, mods);
            }
        }
        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            boolean lastScope = si == sections.size() - 1;
            // Scope header: ├──/╰── then the badge (no trailing space — badge abuts).
            out.append(prefix).append(styling.rail().apply(lastScope ? "╰──" : "├──"))
                    .append(styling.scopeBadge().apply(scopeLabel(s))).append('\n');
            // 4-wide continuation (matching a standard tree node) so the deps nest a
            // space further in than the 3-char scope connector — aligning under the badge.
            String scopePrefix = prefix + styling.rail().apply(lastScope ? "    " : "│   ");
            List<String> mods = bySectionScope.get(s);
            for (int di = 0; di < mods.size(); di++) {
                renderDep(mods.get(di), composite, byModule, dir, depth, maxDepth,
                        di == mods.size() - 1, scopePrefix, styling, members, seenModules, seenDirs, out);
            }
        }
    }

    /** Render one direct dependency, dispatching on its kind (maven / workspace / path / branch-git). */
    private static void renderDep(
            String module, Map<String, Dependency> composite, Map<String, Lockfile.Artifact> byModule,
            Path dir, int depth, int maxDepth, boolean isLast, String prefix, Styling styling,
            Map<String, String> members, Set<String> seenModules, Set<String> seenDirs, StringBuilder out) {

        if (module.startsWith("workspace:")) {
            // Workspace sibling (a `<name>.workspace = true` dep) — already shown
            // at the top level, and its external deps aren't in this member's lock.
            // Resolve the synthetic "workspace:<name>" back to a real coord and
            // reference it (no recursion).
            String name = module.substring("workspace:".length());
            String coord = members.getOrDefault(name, module);
            out.append(prefix).append(styling.rail().apply(isLast ? "╰── " : "├── "))
                    .append(coordLabel(coord, styling))
                    .append(styling.rail().apply(" [workspace]")).append('\n');
            return;
        }
        Dependency comp = composite.get(module);
        if (comp == null) {
            renderNode(byModule, module, depth, maxDepth, isLast, prefix, styling, seenModules, out);
        } else if (comp.isPath()) {
            renderPathNode(comp, module, dir, depth, maxDepth, isLast, prefix, styling,
                    members, seenModules, seenDirs, out);
        } else {
            // branch git dep — annotate (no recursion; needs a clone).
            String ref = ((GitRefSpec.Branch) comp.gitSource().ref()).name();
            out.append(prefix).append(styling.rail().apply(isLast ? "╰── " : "├── "))
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
            boolean isLast, String prefix, Styling styling, Map<String, String> members,
            Set<String> seenModules, Set<String> seenDirs, StringBuilder out) {

        String connector = isLast ? "╰── " : "├── ";
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
        String childPrefix = prefix + styling.rail().apply(isLast ? "    " : "│   ");
        renderComposite(target, targetLock, targetDir, depth + 1, maxDepth, childPrefix,
                styling, members, seenModules, seenDirs, out);
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

        // ╰── for the last child (rounded arc); ├── for the rest.
        // Standard "rounded tree" convention used by eza, tre, etc.
        String connector = isLast ? "╰── " : "├── ";
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

        String childPrefix = prefix + styling.rail().apply(isLast ? "    " : "│   ");
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
