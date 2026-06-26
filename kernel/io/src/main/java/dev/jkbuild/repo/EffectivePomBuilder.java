// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.model.Coordinate;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds {@link EffectivePom}s from a {@link MavenRepo}, merging the parent chain and inlining BOM
 * imports.
 *
 * <p>Merge rules (matching Maven where simple, simplifying where Maven is pathological):
 *
 * <ul>
 *   <li><b>Properties:</b> walk parent chain root→leaf; child overrides parent on key collision.
 *       Implicit {@code project.*} keys are always derived from the *child*'s own coordinates.
 *   <li><b>dependencyManagement:</b> processed root→leaf in declaration order. {@code
 *       import}/{@code pom} entries are expanded by recursively building the referenced BOM and
 *       inlining its managed deps. Later entries override earlier on {@code groupId:artifactId}
 *       collisions.
 *   <li><b>dependencies:</b> root→leaf union, deduped by {@code groupId:artifactId}; child wins.
 *   <li><b>version backfill:</b> any dep without a version is filled from the merged
 *       dependencyManagement.
 * </ul>
 *
 * <p>Hard depth cap of {@value #MAX_DEPTH} on the parent chain and BOM import chain to fail loudly
 * rather than recurse forever.
 */
public final class EffectivePomBuilder {

    static final int MAX_DEPTH = 16;
    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)\\}");

    private final RepoGroup repos;
    private final Map<String, EffectivePom> cache = new HashMap<>();

    public EffectivePomBuilder(MavenRepo repo) {
        this(RepoGroup.of(repo));
    }

    public EffectivePomBuilder(RepoGroup repos) {
        this.repos = Objects.requireNonNull(repos, "repos");
    }

    public EffectivePom build(Coordinate coord) throws IOException, InterruptedException {
        return buildInternal(coord, new HashSet<>(), 0);
    }

    private EffectivePom buildInternal(Coordinate coord, Set<String> visiting, int depth)
            throws IOException, InterruptedException {
        if (depth > MAX_DEPTH) {
            throw new PomParseException("POM parent / BOM chain deeper than " + MAX_DEPTH + " at " + coord);
        }
        String key = coord.toGav();
        if (cache.containsKey(key)) return cache.get(key);
        if (!visiting.add(key)) {
            throw new PomParseException("cycle in POM chain at " + key + " (already visiting: " + visiting + ")");
        }
        try {
            RepoGroup.RepoFetched hit = repos.tryFetchPom(coord)
                    .orElseThrow(() ->
                            new MavenRepo.ArtifactNotFoundException("POM not found in any declared repo: " + coord));
            Pom raw = PomParser.parse(Files.readAllBytes(hit.fetched().cachePath()));
            EffectivePom effective = merge(raw, visiting, depth);
            cache.put(key, effective);
            return effective;
        } finally {
            visiting.remove(key);
        }
    }

    private EffectivePom merge(Pom child, Set<String> visiting, int depth) throws IOException, InterruptedException {

        EffectivePom parent = null;
        if (child.parent() != null) {
            Pom.Parent p = child.parent();
            parent = buildInternal(Coordinate.of(p.groupId(), p.artifactId(), p.version()), visiting, depth + 1);
        }

        // 1. Coords — child wins, parent fills holes.
        String groupId = child.groupId() != null ? child.groupId() : (parent != null ? parent.groupId() : null);
        String version = child.version() != null ? child.version() : (parent != null ? parent.version() : null);
        if (groupId == null || version == null) {
            throw new PomParseException("cannot determine effective groupId/version for " + child.artifactId());
        }

        // 2. Properties — parent first, child overrides. Implicit project.* always come from child.
        Map<String, String> props = new LinkedHashMap<>();
        if (parent != null) props.putAll(parent.properties());
        props.putAll(child.properties());
        props.put("project.groupId", groupId);
        props.put("project.artifactId", child.artifactId());
        props.put("project.version", version);
        props.put("project.packaging", child.packaging());

        // 3. Managed deps — parent first, then child. BOM imports get expanded recursively.
        List<Pom.Dep> mergedManaged = new ArrayList<>();
        if (parent != null) mergedManaged.addAll(parent.managedDependencies());
        for (Pom.Dep dep : child.managedDependencies()) {
            if (isBomImport(dep)) {
                EffectivePom bom = buildInternal(
                        Coordinate.of(dep.groupId(), dep.artifactId(), substitute(dep.version(), props)),
                        visiting,
                        depth + 1);
                mergedManaged.addAll(bom.managedDependencies());
            } else {
                mergedManaged.add(dep);
            }
        }
        mergedManaged = substituteAll(dedupeByModule(mergedManaged), props);

        // 4. Effective deps — parent first, child overrides by module.
        List<Pom.Dep> mergedDeps = new ArrayList<>();
        if (parent != null) mergedDeps.addAll(parent.dependencies());
        mergedDeps.addAll(child.dependencies());
        mergedDeps = dedupeByModule(mergedDeps);

        // 5. Apply dependencyManagement defaults. Maven fills in not just the
        //    version but the scope / type / classifier / exclusions a dep leaves
        //    unspecified — e.g. google-java-format declares guava-testlib with no
        //    version OR scope, inheriting <scope>test</scope> from its parent's
        //    management; backfilling only the version would leak it (and the
        //    guava-android it drags in) onto the compile classpath.
        Map<String, Pom.Dep> managedByModule = new HashMap<>();
        for (Pom.Dep m : mergedManaged) managedByModule.put(m.module(), m);
        List<Pom.Dep> finalDeps = new ArrayList<>(mergedDeps.size());
        for (Pom.Dep dep : mergedDeps) {
            Pom.Dep managed = managedByModule.get(dep.module());
            if (managed != null) {
                dep = applyManagedDefaults(dep, managed);
            }
            finalDeps.add(dep);
        }
        finalDeps = substituteAll(finalDeps, props);

        return new EffectivePom(
                groupId, child.artifactId(), version, child.packaging(), props, finalDeps, mergedManaged);
    }

    // --- merge helpers -----------------------------------------------------

    private static boolean isBomImport(Pom.Dep dep) {
        return "import".equals(dep.scope()) && "pom".equals(dep.type());
    }

    /** Keeps the last occurrence per groupId:artifactId, preserving insertion order otherwise. */
    private static List<Pom.Dep> dedupeByModule(List<Pom.Dep> deps) {
        LinkedHashMap<String, Pom.Dep> byModule = new LinkedHashMap<>();
        for (Pom.Dep dep : deps) byModule.put(dep.module(), dep);
        return new ArrayList<>(byModule.values());
    }

    private static List<Pom.Dep> substituteAll(List<Pom.Dep> deps, Map<String, String> props) {
        List<Pom.Dep> out = new ArrayList<>(deps.size());
        for (Pom.Dep d : deps) {
            out.add(new Pom.Dep(
                    substitute(d.groupId(), props),
                    substitute(d.artifactId(), props),
                    substitute(d.version(), props),
                    substitute(d.scope(), props),
                    d.optional(),
                    substitute(d.classifier(), props),
                    substitute(d.type(), props),
                    d.exclusions()));
        }
        return out;
    }

    /**
     * Fill in the version / scope / type / classifier / exclusions a dependency leaves unspecified
     * from its {@code dependencyManagement} entry (a declared value on the dependency itself always
     * wins). Mirrors Maven: management supplies defaults for all of these, not just the version.
     */
    private static Pom.Dep applyManagedDefaults(Pom.Dep dep, Pom.Dep managed) {
        String version = blank(dep.version()) ? managed.version() : dep.version();
        String scope = blank(dep.scope()) ? managed.scope() : dep.scope();
        String type = blank(dep.type()) ? managed.type() : dep.type();
        String classifier = blank(dep.classifier()) ? managed.classifier() : dep.classifier();
        List<Pom.Dep.Exclusion> exclusions =
                (dep.exclusions() == null || dep.exclusions().isEmpty()) ? managed.exclusions() : dep.exclusions();
        return new Pom.Dep(
                dep.groupId(), dep.artifactId(), version, scope, dep.optional(), classifier, type, exclusions);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String substitute(String raw, Map<String, String> ctx) {
        if (raw == null) return null;
        // Iterate up to a small fixed budget to resolve chained refs like ${a} → ${b} → "x".
        String current = raw;
        for (int pass = 0; pass < 4; pass++) {
            Matcher m = PROPERTY_REF.matcher(current);
            if (!m.find()) return current;
            m.reset();
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String key = m.group(1);
                String value = ctx.get(key);
                if (value != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(value));
                    changed = true;
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                }
            }
            m.appendTail(sb);
            current = sb.toString();
            if (!changed) return current;
        }
        return current;
    }
}
