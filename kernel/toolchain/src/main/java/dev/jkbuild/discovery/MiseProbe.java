// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.JdkHit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * <a href="https://mise.jdx.dev">mise</a> (formerly rtx) keeps installs
 * under {@code <data-dir>/installs/<kind>/<version>/}. The data dir
 * resolves in priority order:
 *
 * <ol>
 *   <li>{@code $MISE_DATA_DIR} if set</li>
 *   <li>{@code $XDG_DATA_HOME/mise} if {@code XDG_DATA_HOME} is set</li>
 *   <li>{@code ~/.local/share/mise} (the documented default)</li>
 * </ol>
 *
 * <p>mise's JDK identifiers vary by request style — {@code 21.0.5},
 * {@code temurin-21.0.5}, {@code corretto-21.0.5} all appear as direct
 * subdir names. {@link ProbeSupport#discoverJdk} treats each as opaque and
 * reads the {@code release} file for the actual version + vendor.
 */
public final class MiseProbe implements LocalToolProbe {

    private final Path dataDir;

    public MiseProbe() {
        this(resolveDataDir(System::getenv, System.getProperty("user.home", "")));
    }

    MiseProbe(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public String name() {
        return "mise";
    }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        Path kindDir = dataDir.resolve("installs").resolve(spec.kind());
        if (!Files.isDirectory(kindDir)) return Optional.empty();
        try (Stream<Path> entries = Files.list(kindDir)) {
            return entries.filter(Files::isDirectory)
                    // Skip mise's `latest` symlink so we don't double-report.
                    .filter(p -> !"latest".equals(p.getFileName().toString()))
                    .map(path -> {
                        try {
                            return path.toRealPath();
                        } catch (IOException e) {
                            return path;
                        }
                    })
                    .filter(path -> ToolHealth.isHealthy(spec, path))
                    .findFirst()
                    .map(path -> new DiscoveredTool(path, spec.version(), name()));
        }
    }

    @Override
    public List<JdkHit> discoverAllJdks() throws IOException {
        Path javaDir = dataDir.resolve("installs").resolve("java");
        if (!Files.isDirectory(javaDir)) return List.of(); // fail fast
        List<JdkHit> hits = new ArrayList<>();
        try (Stream<Path> entries = Files.list(javaDir)) {
            entries.filter(Files::isDirectory)
                    .filter(p -> !"latest".equals(p.getFileName().toString()))
                    .forEach(p -> ProbeSupport.discoverJdk(p, name()).ifPresent(hits::add));
        }
        return hits;
    }

    /** Resolve mise's data dir per <a href="https://mise.jdx.dev/configuration.html">mise's config docs</a>. */
    static Path resolveDataDir(Function<String, String> env, String userHome) {
        String miseData = env.apply("MISE_DATA_DIR");
        if (miseData != null && !miseData.isBlank()) return Path.of(miseData);
        String xdg = env.apply("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg, "mise");
        return Path.of(userHome, ".local", "share", "mise");
    }
}
