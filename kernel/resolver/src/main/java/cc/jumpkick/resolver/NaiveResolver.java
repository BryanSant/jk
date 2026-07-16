// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.Pom;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Naive breadth-first resolver. Walks each declared dep's {@link EffectivePom}, follows {@code
 * compile} and {@code runtime} scopes only, and picks the highest version it sees per module —
 * Gradle / Cargo semantics.
 *
 * <p>v0.1 limitations (all addressed by PubGrub in the next session):
 *
 * <ul>
 *   <li>Version selectors are treated as exact pins. Caret / tilde / range resolution and {@code
 *       maven-metadata.xml} lookups arrive with PubGrub.
 *   <li>No prose diagnostics on conflict — the "highest wins" pick is silent.
 *   <li>Optional transitives and {@code &lt;exclusions&gt;} are ignored.
 * </ul>
 */
public final class NaiveResolver implements Resolver {

    private static final Set<String> FOLLOWED_SCOPES = Set.of("compile", "runtime");

    private static boolean shouldFollow(String scope) {
        return scope == null || scope.isEmpty() || FOLLOWED_SCOPES.contains(scope);
    }

    private final EffectivePomBuilder pomBuilder;

    public NaiveResolver(EffectivePomBuilder pomBuilder) {
        this.pomBuilder = Objects.requireNonNull(pomBuilder, "pomBuilder");
    }

    @Override
    public Resolution resolve(List<Dependency> roots) throws IOException, InterruptedException {
        Map<String, Pick> picked = new HashMap<>();
        Deque<WorkItem> work = new ArrayDeque<>();
        for (Dependency root : roots) {
            work.add(new WorkItem(root.module(), extractVersion(root.version())));
        }

        while (!work.isEmpty()) {
            WorkItem item = work.poll();
            Pick existing = picked.get(item.module);
            if (existing != null && Versions.compare(existing.version, item.version) >= 0) {
                continue; // already at >= this version; nothing to do
            }
            EffectivePom pom = pomBuilder.build(toCoord(item));
            List<String> transitiveCoords = new ArrayList<>();
            for (Pom.Dep dep : pom.dependencies()) {
                if (dep.optional()) continue;
                if (!shouldFollow(dep.scope())) continue;
                if (dep.version() == null || dep.version().isBlank()) continue;
                transitiveCoords.add(dep.module() + "@" + dep.version());
                work.add(new WorkItem(dep.module(), dep.version()));
            }
            picked.put(item.module, new Pick(item.version, transitiveCoords));
        }

        Map<String, Resolution.ResolvedModule> out = new TreeMap<>();
        for (Map.Entry<String, Pick> e : picked.entrySet()) {
            out.put(e.getKey(), new Resolution.ResolvedModule(e.getKey(), e.getValue().version, e.getValue().deps));
        }
        return new Resolution(out);
    }

    private static String extractVersion(VersionSelector selector) {
        return switch (selector) {
            case VersionSelector.Caret c -> c.version();
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Tilde t -> t.version();
            case VersionSelector.Range r ->
                throw new IllegalArgumentException(
                        "naive resolver cannot resolve ranges yet (use PubGrub): " + r.raw());
            case VersionSelector.Latest l ->
                throw new IllegalArgumentException("naive resolver cannot resolve 'latest' yet (use PubGrub)");
        };
    }

    private static Coordinate toCoord(WorkItem item) {
        return Coordinate.ofModule(item.module, item.version);
    }

    private record WorkItem(String module, String version) {}

    private record Pick(String version, List<String> deps) {}
}
