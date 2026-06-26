// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import dev.jkbuild.resolver.Versions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * In-memory {@link PackageSource} for solver tests. Pure data; no I/O.
 *
 * <pre>{@code
 * PackageSource src = InMemoryPackageSource.builder()
 *     .version("root", "1.0", deps -> deps
 *         .require("widget", VersionSet.exact("2.0")))
 *     .version("widget", "2.0")
 *     .version("widget", "1.0")
 *     .build();
 * }</pre>
 */
public final class InMemoryPackageSource implements PackageSource {

    private final Map<String, List<String>> versionsByPackage;
    private final Map<String, List<Term>> depsByCoord;

    private InMemoryPackageSource(Map<String, List<String>> versionsByPackage, Map<String, List<Term>> depsByCoord) {
        this.versionsByPackage = Map.copyOf(versionsByPackage);
        this.depsByCoord = Map.copyOf(depsByCoord);
    }

    @Override
    public List<String> versions(String pkg) {
        return versionsByPackage.getOrDefault(pkg, List.of());
    }

    @Override
    public List<Term> dependencies(String pkg, String version) {
        return depsByCoord.getOrDefault(coord(pkg, version), List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String coord(String pkg, String version) {
        return pkg + "@" + version;
    }

    public static final class Builder {
        private final Map<String, List<String>> versionsByPackage = new HashMap<>();
        private final Map<String, List<Term>> depsByCoord = new HashMap<>();

        public Builder version(String pkg, String version) {
            return version(pkg, version, deps -> {});
        }

        public Builder version(String pkg, String version, Consumer<Deps> deps) {
            Objects.requireNonNull(pkg, "pkg");
            Objects.requireNonNull(version, "version");
            versionsByPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(version);
            Deps captured = new Deps();
            deps.accept(captured);
            depsByCoord.put(coord(pkg, version), List.copyOf(captured.entries));
            return this;
        }

        public InMemoryPackageSource build() {
            // Sort each version list highest-first to match the PackageSource contract.
            Map<String, List<String>> sorted = new HashMap<>();
            versionsByPackage.forEach((pkg, list) -> {
                List<String> copy = new ArrayList<>(list);
                copy.sort((a, b) -> Versions.compare(b, a)); // descending
                sorted.put(pkg, List.copyOf(copy));
            });
            return new InMemoryPackageSource(sorted, depsByCoord);
        }
    }

    public static final class Deps {
        private final List<Term> entries = new ArrayList<>();

        public Deps require(String pkg, VersionSet versions) {
            entries.add(Term.positive(pkg, versions));
            return this;
        }
    }
}
