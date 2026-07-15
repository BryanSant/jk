// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.tool;

import build.jumpkick.cache.Cas;
import build.jumpkick.http.Http;
import build.jumpkick.model.Coordinate;
import build.jumpkick.model.Dependency;
import build.jumpkick.model.ToolCoordSpec;
import build.jumpkick.model.VersionSelector;
import build.jumpkick.repo.EffectivePomBuilder;
import build.jumpkick.repo.MavenRepo;
import build.jumpkick.repo.RepoGroup;
import build.jumpkick.resolver.NaiveResolver;
import build.jumpkick.resolver.Resolution;
import build.jumpkick.resolver.Resolver;
import build.jumpkick.resolver.Versions;
import build.jumpkick.resolver.VersionSelectors;
import build.jumpkick.resolver.pubgrub.VersionSet;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a Maven coord into a {@link ToolEnv} ready for {@link ToolLauncher} to install or exec
 * (PRD §20).
 *
 * <p>Pipeline:
 *
 * <ol>
 *   <li>{@link NaiveResolver} walks the primary coord's effective POM to collect transitive {@code
 *       compile}/{@code runtime} deps.
 *   <li>Each resolved coord's jar is fetched into the {@link Cas} via {@link RepoGroup}.
 *   <li>The primary jar's {@code META-INF/MANIFEST.MF Main-Class} attribute is read; a
 *       caller-provided override wins if present.
 *   <li>The result carries the classpath in resolution order (primary first, then transitives).
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

    /**
     * Resolve a {@link ToolCoordSpec} — pinning a {@link ToolCoordSpec.Floating} spec against the
     * repos' version list first (highest match; {@code latest} considers stable releases only),
     * then running the normal pipeline. {@code withSpecs} are {@code --with} injections (PRD
     * §20.1): additional root deps, each pinned the same way, resolved into the env's classpath
     * alongside the primary.
     */
    public ToolEnv resolve(ToolCoordSpec spec, String binName, String mainClassOverride, List<ToolCoordSpec> withSpecs)
            throws IOException, InterruptedException {
        Coordinate primary = pin(spec);
        List<Dependency> extras = new ArrayList<>();
        for (ToolCoordSpec w : withSpecs) {
            Coordinate c = pin(w);
            extras.add(new Dependency(c.module(), VersionSelector.parse("=" + c.version())));
        }
        return resolve(primary, binName, mainClassOverride, extras);
    }

    private Coordinate pin(ToolCoordSpec spec) throws IOException, InterruptedException {
        return switch (spec) {
            case ToolCoordSpec.Pinned p -> p.coordinate();
            case ToolCoordSpec.Floating f ->
                Coordinate.of(f.group(), f.artifact(), pickVersion(f.module(), f.selector()));
        };
    }

    public ToolEnv resolve(Coordinate primary, String binName, String mainClassOverride)
            throws IOException, InterruptedException {
        return resolve(primary, binName, mainClassOverride, List.of());
    }

    public ToolEnv resolve(Coordinate primary, String binName, String mainClassOverride, List<Dependency> extras)
            throws IOException, InterruptedException {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(binName, "binName");
        Objects.requireNonNull(extras, "extras");

        // 0. A published native binary for this platform beats the JVM path (PRD §20.4) —
        // unless the caller pinned a Main-Class, which only makes sense on the JVM.
        if (mainClassOverride == null || mainClassOverride.isBlank()) {
            var nativeBinary = fetchNativeBinary(primary);
            if (nativeBinary.isPresent()) {
                Path bin = nativeBinary.get();
                //noinspection ResultOfMethodCallIgnored — best-effort; exec fails loudly if it didn't stick
                bin.toFile().setExecutable(true, false);
                return new ToolEnv(binName, primary, ToolEnv.NATIVE_BINARY, List.of(bin));
            }
        }

        // 1. Transitive resolution from the primary coord (+ any --with extras).
        Resolver resolver = new NaiveResolver(new EffectivePomBuilder(repos));
        Dependency root = new Dependency(
                primary.group() + ":" + primary.artifact(), VersionSelector.parse("=" + primary.version()));
        List<Dependency> roots = new ArrayList<>();
        roots.add(root);
        roots.addAll(extras);
        Resolution resolution = resolver.resolve(roots);

        // 2. Fetch each resolved jar. Primary first so classpath order is stable.
        Path primaryJar = fetchJar(primary);
        List<Path> classpath = new ArrayList<>();
        classpath.add(primaryJar);
        String primaryKey = primary.group() + ":" + primary.artifact();
        for (Resolution.ResolvedModule m : resolution.modules().values()) {
            if (m.module().equals(primaryKey)) continue;
            classpath.add(fetchJar(m.coordinate()));
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

    /**
     * Pin a floating selector against the union of versions the repos advertise (maven-metadata,
     * TTL-cached). Highest match wins; {@code latest} considers {@linkplain Versions#isStable
     * stable} releases only, falling back to the overall highest when nothing stable exists.
     */
    private String pickVersion(String module, VersionSelector selector) throws IOException, InterruptedException {
        if (selector instanceof VersionSelector.Exact e) return e.version();
        List<String> available = repos.availableVersions(Coordinate.ofModule(module, "any"));
        if (available.isEmpty()) {
            throw new MavenRepo.ArtifactNotFoundException(
                    "no versions of " + module + " found in any declared repo");
        }
        VersionSet set = VersionSelectors.toVersionSet(selector);
        List<String> matching =
                available.stream().filter(set::contains).toList();
        if (selector instanceof VersionSelector.Latest) {
            List<String> stable = matching.stream().filter(Versions::isStable).toList();
            if (!stable.isEmpty()) matching = stable;
        }
        return matching.stream()
                .max(Versions::compare)
                .orElseThrow(() -> new MavenRepo.ArtifactNotFoundException(
                        "no version of " + module + " matches " + selector.raw() + " (available: "
                                + String.join(", ", available) + ")"));
    }

    /**
     * Probe for a platform-native binary of {@code primary}: the PRD §20.4 classifier convention
     * ({@code native-<arch>-<os>}) first, then the protoc-style one ({@code <os>-<arch>} with
     * {@code osx}/{@code aarch_64} vocabulary). Both use packaging type {@code exe}. Empty when
     * the repo publishes neither — the normal jar path takes over silently.
     */
    private java.util.Optional<Path> fetchNativeBinary(Coordinate primary) throws IOException, InterruptedException {
        String os = build.jumpkick.jdk.HostPlatform.currentOs();
        String arch = build.jumpkick.jdk.HostPlatform.currentArch();
        if (build.jumpkick.jdk.HostPlatform.UNSUPPORTED.equals(os)
                || build.jumpkick.jdk.HostPlatform.UNSUPPORTED.equals(arch)) {
            return java.util.Optional.empty();
        }
        String protocOs = "macos".equals(os) ? "osx" : os;
        String protocArch = "aarch64".equals(arch) ? "aarch_64" : arch;
        List<String> classifiers = List.of("native-" + arch + "-" + os, protocOs + "-" + protocArch);
        for (String classifier : classifiers) {
            var fetched = repos.tryFetchArtifact(
                    new Coordinate(primary.group(), primary.artifact(), primary.version(), classifier, "exe"));
            if (fetched.isPresent()) {
                return java.util.Optional.of(fetched.get().fetched().cachePath());
            }
        }
        return java.util.Optional.empty();
    }

    private Path fetchJar(Coordinate coord) throws IOException, InterruptedException {
        return repos.tryFetchArtifact(coord)
                .orElseThrow(
                        () -> new MavenRepo.ArtifactNotFoundException("jar not found in any declared repo: " + coord))
                .fetched()
                .cachePath();
    }
}
