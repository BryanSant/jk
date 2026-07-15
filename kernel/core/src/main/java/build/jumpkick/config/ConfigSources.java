// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import build.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The ordered set of TOML config files jk merges, lowest precedence first. This is the single
 * source of truth for <em>where</em> configuration lives and <em>in what order</em> layers win, so
 * every typed view ({@link JkConfig}, {@link JkCacheConfig}, {@link ForgeAuthConfig}, {@link
 * GlobalConfig}, …) reads the same files in the same order rather than each re-deriving paths.
 *
 * <p>File layers, lowest → highest precedence:
 *
 * <ol>
 *   <li><b>User-global</b> {@code ~/.jk/config.toml} — resolved via {@link
 *       JkDirs#userConfigFile()}, so {@code JK_HOME} and {@code JK_CONFIG_FILE} relocate it like
 *       every other jk path.
 *   <li><b>Project</b> {@code jk.toml} — the nearest one walking up from the start directory — or
 *       an explicit {@code --config-file} that replaces it.
 * </ol>
 *
 * <p>There is deliberately <strong>no</strong> system layer ({@code /etc/jk}) and jk never reads
 * {@code ~/.config}: jk owns its tree under {@code ~/.jk} the way Cargo owns {@code ~/.cargo} (see
 * {@link JkDirs}). Both were dropped when the on-disk layout consolidated under {@code ~/.jk}; do
 * not reintroduce them.
 *
 * <p>Environment variables ({@code JK_*}, {@code NO_COLOR}, …) and CLI flags sit <em>above</em>
 * every file layer in precedence — they are applied by each loader (see {@link JkConfigLoader}) and
 * {@link EnvValues} after the file layers fold, never modelled as files here.
 */
public final class ConfigSources {

    private final List<Path> layers;

    private ConfigSources(List<Path> layers) {
        this.layers = List.copyOf(layers);
    }

    /**
     * The file layers to merge, lowest precedence first. Paths are not guaranteed to exist; loaders
     * skip absent/unreadable files. Empty when {@code noConfig} short-circuits file discovery.
     */
    public List<Path> layers() {
        return layers;
    }

    /**
     * Discover the layers for a run. {@code startDir} is the search root for the project {@code
     * jk.toml} (normally the working directory). {@code noConfig} ({@code --no-config}) drops all
     * file layers — env vars and CLI flags still apply. {@code explicitConfigFile} ({@code
     * --config-file}) replaces the project layer; the user-global layer still merges underneath.
     */
    public static ConfigSources discover(Path startDir, boolean noConfig, Optional<Path> explicitConfigFile) {
        if (noConfig) return new ConfigSources(List.of());
        List<Path> out = new ArrayList<>(2);
        out.add(JkDirs.userConfigFile());
        if (explicitConfigFile.isPresent()) {
            out.add(explicitConfigFile.get());
        } else {
            Path project = findProjectConfig(startDir);
            if (project != null) out.add(project);
        }
        return new ConfigSources(out);
    }

    /** The user-global config file, {@code ~/.jk/config.toml}. */
    public static Path userConfig() {
        return JkDirs.userConfigFile();
    }

    /**
     * Search {@code startDir} and its ancestors for the nearest {@code jk.toml}; {@code null} when
     * none is found before the filesystem root.
     */
    public static Path findProjectConfig(Path startDir) {
        Path here = startDir == null ? null : startDir.toAbsolutePath().normalize();
        while (here != null) {
            Path candidate = here.resolve("jk.toml");
            if (Files.isRegularFile(candidate)) return candidate;
            here = here.getParent();
        }
        return null;
    }
}
