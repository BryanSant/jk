// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.protocol.WhyReport;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.DependencyTree;
import cc.jumpkick.resolver.Provenance;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Engine-hosted {@code jk tree} / {@code jk why} (thin-client contract): both need the parsed
 * project + lock (and tree walks path-dep composites, re-parsing module tomls), so the graph
 * reasoning runs engine-side. The tree renders with {@link DependencyTree.Styling#markers()} —
 * the client, which owns the Theme, substitutes the marker tags with its real stylers.
 */
public final class GraphOps {

    private GraphOps() {}

    /** The marker-tagged tree, ready for {@link DependencyTree#applyStyling} client-side. */
    public static String treeRender(Path dir, int maxDepth, boolean flatten, boolean stack, List<String> scopeNames)
            throws IOException {
        JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
        Lockfile lock = LockfileReader.read(dir.resolve("jk.lock"));
        List<Scope> scopes = scopeNames.isEmpty()
                ? null
                : scopeNames.stream().map(Scope::fromCanonical).toList();
        return DependencyTree.render(
                project, lock, dir, maxDepth, DependencyTree.Styling.markers(), flatten, scopes, stack);
    }

    public static WhyReport why(Path dir, String query) {
        try {
            JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
            Lockfile lock = LockfileReader.read(dir.resolve("jk.lock"));
            List<Lockfile.Artifact> matches = lock.artifacts().stream()
                    .filter(p -> matchesQuery(p.name(), query))
                    .toList();
            List<String> names = new ArrayList<>(matches.size());
            List<String> versions = new ArrayList<>(matches.size());
            List<String> owners = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            for (int i = 0; i < matches.size(); i++) {
                Lockfile.Artifact target = matches.get(i);
                names.add(target.name());
                versions.add(target.version());
                for (Provenance.Path path : Provenance.pathsTo(project, lock, target.name())) {
                    owners.add(Integer.toString(i));
                    paths.add(path.steps().stream()
                            .map(s -> s.module() + "@" + s.version())
                            .collect(Collectors.joining(">")));
                }
            }
            return new WhyReport(null, names, versions, owners, paths);
        } catch (IOException | RuntimeException e) {
            return WhyReport.error(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Match a lockfile {@code group:artifact} name against a user query. Exact match, artifact-only
     * match (query has no colon), or substring — the contract {@code jk why} has always had.
     */
    private static boolean matchesQuery(String name, String query) {
        if (name.equals(query)) return true;
        if (!query.contains(":")) {
            if (name.endsWith(":" + query)) return true;
            return name.contains(query);
        }
        return false;
    }
}
