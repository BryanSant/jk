// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable view of the merged user/project configuration. Built by {@link JkConfigLoader} (file
 * layers from {@link ConfigSources}) which honours the precedence:
 *
 * <ol>
 *   <li>command-line flag (highest — applied by callers AFTER load)
 *   <li>environment variable
 *   <li>project-local {@code jk.toml} (from {@code [config]} table)
 *   <li>user-global {@code ~/.jk/config.toml} (from {@code [config]} table)
 * </ol>
 *
 * <p>There is no {@code /etc/jk} system layer and jk never reads {@code ~/.config} — see {@link
 * ConfigSources}.
 *
 * <p>Each setting is an {@link Optional} — empty means "no layer set this; use the built-in
 * default". A consumer asks the config and falls back to its own default when the answer is empty.
 * This keeps the "what does the user actually want" decision in one place.
 *
 * <p>This model only carries CLI-wide settings (color, offline, progress, working-directory hint,
 * …). Project-specific data like dependency declarations stays in {@link
 * cc.jumpkick.model.JkBuild}; this is the thin slice the runtime needs before any project parsing
 * happens.
 */
public record JkConfig(
        Optional<ColorChoice> color,
        Optional<Boolean> offline,
        /**
         * {@code --rebuild} — bypass jk's own build caches (action cache, freshness stamps)
         * WITHOUT re-fetching locked dependencies: a genuine recompile+repackage+test-rerun that
         * still serves artifacts from the local CAS and works offline. Also {@code jk verify}'s
         * scratch-rebuild lane. Implied by {@code force} (which additionally re-fetches).
         */
        Optional<Boolean> rebuild,
        Optional<Boolean> noProgress,
        Optional<Boolean> quiet,
        Optional<Boolean> verbose,
        Optional<Path> directory,
        /**
         * {@code --force} / {@code JK_FORCE} — bypass all of jk's caching for this invocation
         * (recompile, re-resolve, rerun tests).
         */
        Optional<Boolean> force,
        /**
         * {@code --no-ansi} — disable all ANSI escape sequences including color, bold, italic,
         * and cursor movement. Output is ASCII-only. Distinct from {@code --color never} which
         * strips color but preserves text attributes (bold/italic).
         */
        Optional<Boolean> noAnsi) {

    public enum ColorChoice {
        AUTO,
        ALWAYS,
        NEVER;

        public static Optional<ColorChoice> parse(String s) {
            if (s == null || s.isBlank()) return Optional.empty();
            return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "auto" -> Optional.of(AUTO);
                case "always" -> Optional.of(ALWAYS);
                case "never" -> Optional.of(NEVER);
                default -> Optional.empty();
            };
        }
    }

    public JkConfig {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(offline, "offline");
        Objects.requireNonNull(noProgress, "noProgress");
        Objects.requireNonNull(quiet, "quiet");
        Objects.requireNonNull(verbose, "verbose");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(force, "force");
        Objects.requireNonNull(noAnsi, "noAnsi");
    }

    /** Empty config — every setting unset. Used as the seed before layers merge. */
    public static JkConfig empty() {
        return new JkConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * Return a new config with {@code over}'s set values laid on top of this one's. {@code over} wins
     * where it has a value; otherwise this config's value passes through.
     */
    public JkConfig mergedWith(JkConfig over) {
        return new JkConfig(
                over.color.or(() -> this.color),
                over.offline.or(() -> this.offline),
                over.rebuild.or(() -> this.rebuild),
                over.noProgress.or(() -> this.noProgress),
                over.quiet.or(() -> this.quiet),
                over.verbose.or(() -> this.verbose),
                over.directory.or(() -> this.directory),
                over.force.or(() -> this.force),
                over.noAnsi.or(() -> this.noAnsi));
    }

    /** Convenience: color with a fallback when empty. */
    public ColorChoice colorOr(ColorChoice fallback) {
        return color.orElse(fallback);
    }

    public boolean offlineOr(boolean fallback) {
        return offline.orElse(fallback);
    }

    /** True when {@code --force} / {@code JK_FORCE} was set for this invocation. */
    public boolean forceOr(boolean fallback) {
        return force.orElse(fallback);
    }

    /**
     * True when this build must bypass jk's own caches ({@code force} implies it). NOT
     * {@code force.or(() -> rebuild)}: {@code Optional.or} short-circuits on PRESENCE, and wire
     * decodes materialize {@code force} as {@code Optional.of(false)} — which silently masked a
     * present-and-true {@code rebuild}.
     */
    public boolean rebuildOr(boolean fallback) {
        if (force.isEmpty() && rebuild.isEmpty()) return fallback;
        return force.orElse(false) || rebuild.orElse(false);
    }

    /** True when {@code --no-ansi} was set — all ANSI sequences suppressed, ASCII only. */
    public boolean noAnsiOr(boolean fallback) {
        return noAnsi.orElse(fallback);
    }

    public boolean noProgressOr(boolean fallback) {
        return noProgress.orElse(fallback);
    }

    public boolean quietOr(boolean fallback) {
        return quiet.orElse(fallback);
    }

    public boolean verboseOr(boolean fallback) {
        return verbose.orElse(fallback);
    }
}
