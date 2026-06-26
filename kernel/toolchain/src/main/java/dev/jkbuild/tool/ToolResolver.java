// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.tool;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.repo.EffectivePomBuilder;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.NaiveResolver;
import dev.jkbuild.resolver.Resolution;
import dev.jkbuild.resolver.Resolver;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a Maven coord into a {@link ToolEnv} ready for
 * {@link ToolLauncher} to install or exec (PRD §20).
 *
 * <p>Pipeline:
 * <ol>
 *   <li>{@link NaiveResolver} walks the primary coord's effective POM to
 *       collect transitive {@code compile}/{@code runtime} deps.</li>
 *   <li>Each resolved coord's jar is fetched into the {@link Cas} via
 *       {@link RepoGroup}.</li>
 *   <li>The primary jar's {@code META-INF/MANIFEST.MF Main-Class}
 *       attribute is read; a caller-provided override wins if present.</li>
 *   <li>The result carries the classpath in resolution order (primary
 *       first, then transitives).</li>
 * </ol>
 */
public final class ToolResolver {

    private final RepoGroup repos;

    public ToolResolver(RepoGroup repos) {
        this.repos = Objects.requireNonNull(repos, "repos");
    }

    /** Convenience: Central-only resolver backed by a shared {@link Cas}. */
    public static ToolResolver mavenCentral(Http http, Cas cas) {
        MavenRepo central = new MavenRepo("central", URI.create("https://repo.maven.apache.org/maven2/"), http, cas);
        return new ToolResolver(RepoGroup.of(central));
    }

    public ToolEnv resolve(Coordinate primary, String binName, String mainClassOverride)
            throws IOException, InterruptedException {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(binName, "binName");

        // 1. Transitive resolution from the primary coord.
        Resolver resolver = new NaiveResolver(new EffectivePomBuilder(repos));
        Dependency root = new Dependency(
                primary.group() + ":" + primary.artifact(), VersionSelector.parse("=" + primary.version()));
        Resolution resolution = resolver.resolve(List.of(root));

        // 2. Fetch each resolved jar. Primary first so classpath order is stable.
        Path primaryJar = fetchJar(primary);
        List<Path> classpath = new ArrayList<>();
        classpath.add(primaryJar);
        String primaryKey = primary.group() + ":" + primary.artifact();
        for (Resolution.ResolvedModule m : resolution.modules().values()) {
            if (m.module().equals(primaryKey)) continue;
            int colon = m.module().indexOf(':');
            Coordinate coord =
                    Coordinate.of(m.module().substring(0, colon), m.module().substring(colon + 1), m.version());
            classpath.add(fetchJar(coord));
        }

        // 3. Main-Class detection.
        String mainClass = mainClassOverride;
        if (mainClass == null || mainClass.isBlank()) {
            Optional<String> fromManifest = JarManifest.mainClass(primaryJar);
            mainClass = fromManifest.orElseThrow(
                    () -> new IOException(primary + " has no Main-Class in its manifest — pass --main <class>."));
        }
        return new ToolEnv(binName, primary, mainClass, classpath);
    }

    private Path fetchJar(Coordinate coord) throws IOException, InterruptedException {
        return repos.tryFetchArtifact(coord)
                .orElseThrow(
                        () -> new MavenRepo.ArtifactNotFoundException("jar not found in any declared repo: " + coord))
                .fetched()
                .cachePath();
    }
}
