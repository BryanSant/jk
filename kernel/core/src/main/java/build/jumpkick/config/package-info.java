// SPDX-License-Identifier: Apache-2.0
/**
 * jk's configuration layer: how user/project preferences are located, read, and
 * merged. Everything here reads the same files in the same order through one
 * shared SPI so behaviour is consistent and testable.
 *
 * <h2>The shared SPI</h2>
 * <ul>
 *   <li>{@link build.jumpkick.config.ConfigSources} — <em>where</em> config lives and
 *       the order layers win: user-global {@code ~/.jk/config.toml} (via
 *       {@link build.jumpkick.util.JkDirs}) then project {@code jk.toml} (or an
 *       explicit {@code --config-file}). No {@code /etc/jk} layer; jk never reads
 *       {@code ~/.config}.</li>
 *   <li>{@link build.jumpkick.config.TomlValues} — <em>how</em> to coerce TOML values
 *       leniently (a bad value becomes "unset", never an exception).</li>
 *   <li>{@link build.jumpkick.config.EnvValues} — <em>how</em> to coerce {@code JK_*}
 *       environment variables, with one jk-wide boolean truth set.</li>
 * </ul>
 *
 * <h2>Two precedence regimes</h2>
 *
 * <p><b>Layered settings</b> — {@code [config]} (and {@code [forge]}): the
 * user-global file holds defaults that a project may override, in turn overridden
 * by env and flags. Lowest → highest:
 * <ol>
 *   <li>user-global {@code ~/.jk/config.toml}</li>
 *   <li>project {@code jk.toml} (nearest ancestor) or {@code --config-file}</li>
 *   <li>{@code JK_*} environment variables</li>
 *   <li>command-line flags (applied by the caller, after load)</li>
 * </ol>
 *
 * <p><b>Non-project settings</b> — {@code [global]} and {@code [cache]}: these
 * describe <em>your machine/terminal</em> (does this terminal have a Nerd Font?
 * how big may this shared cache grow?), not a checked-out project, so a project
 * {@code jk.toml}'s {@code [global]}/{@code [cache]} table is <em>ignored</em>.
 * They are still env-overridable, which keeps jk container- and CI-friendly — an
 * image can tune them via {@code JK_*} without editing a file. Lowest → highest:
 * <ol>
 *   <li>user-global {@code ~/.jk/config.toml}</li>
 *   <li>{@code JK_*} environment variable for the key (e.g. {@code JK_NERDFONT},
 *       {@code JK_MAX_SIZE_GB})</li>
 * </ol>
 * The env var name is {@code JK_} + the key upper-cased with {@code -} → {@code _}
 * ({@code max-size-gb} → {@code JK_MAX_SIZE_GB}); the project layer is simply
 * skipped relative to the layered regime above.
 *
 * <h2>The typed views</h2>
 * Each group of settings is its own immutable view built on the SPI:
 * <ul>
 *   <li>{@link build.jumpkick.config.JkConfig} + {@link build.jumpkick.config.JkConfigLoader}
 *       — CLI-wide scalar toggles ({@code [config]}: color, offline, quiet, …),
 *       <em>layered</em> with full project/env/CLI precedence.</li>
 *   <li>{@link build.jumpkick.config.ForgeAuthConfig} — {@code [forge]} OAuth client
 *       IDs, <em>layered</em> (user &lt; project).</li>
 *   <li>{@link build.jumpkick.config.JkCacheConfig} — {@code [cache]} prune policy,
 *       <em>non-project</em> (env &gt; user;
 *       {@link build.jumpkick.config.JkCacheConfig#resolve()}).</li>
 *   <li>{@link build.jumpkick.config.GlobalConfig} — {@code [global]} UI preferences
 *       (e.g. {@code nerdfont}), <em>non-project</em> (env &gt; user, e.g.
 *       {@code JK_NERDFONT}).</li>
 * </ul>
 * Views are intentionally <em>not</em> a class hierarchy — they are different
 * shapes (scalar record, host-keyed map, single flag) that <em>compose</em> the
 * same SPI rather than inherit from a common base.
 *
 * <h2>Adding a setting</h2>
 * <ol>
 *   <li>Add it to the typed view that owns its table (or create a new view for a
 *       new table). Read the value with {@link build.jumpkick.config.TomlValues}.</li>
 *   <li>If it has a {@code JK_*} override, read that with
 *       {@link build.jumpkick.config.EnvValues} and apply it above the file layers.</li>
 *   <li>Expose a static {@code from(…)} that takes an explicit path / env function
 *       so unit tests drive it hermetically — never read {@link System#getenv} or
 *       a real home directory directly in the parse path.</li>
 *   <li>Default leniently: a missing or malformed value falls back to the
 *       documented default; configuration never fails a command.</li>
 * </ol>
 */
package build.jumpkick.config;
