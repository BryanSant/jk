// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

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
 * dev.jkbuild.model.JkBuild}; this is the thin slice the runtime needs before any project parsing
 * happens.
 */
public record JkConfig(
        Optional<ColorChoice> color,
        Optional<Boolean> offline,
        Optional<Boolean> rerun,
        Optional<Boolean> refresh,
        Optional<Boolean> noProgress,
        Optional<Boolean> quiet,
        Optional<Boolean> verbose,
        Optional<Path> directory,
        /**
         * {@code --force} / {@code JK_FORCE} — bypass all of jk's caching for this invocation.
         * Supersedes both {@code rerun} and {@code refresh}: any code that checks either of those
         * will also see {@code true} when {@code force} is set.
         */
        Optional<Boolean> force) {

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
        Objects.requireNonNull(rerun, "rerun");
        Objects.requireNonNull(refresh, "refresh");
        Objects.requireNonNull(noProgress, "noProgress");
        Objects.requireNonNull(quiet, "quiet");
        Objects.requireNonNull(verbose, "verbose");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(force, "force");
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
                over.rerun.or(() -> this.rerun),
                over.refresh.or(() -> this.refresh),
                over.noProgress.or(() -> this.noProgress),
                over.quiet.or(() -> this.quiet),
                over.verbose.or(() -> this.verbose),
                over.directory.or(() -> this.directory),
                over.force.or(() -> this.force));
    }

    /** Convenience: color with a fallback when empty. */
    public ColorChoice colorOr(ColorChoice fallback) {
        return color.orElse(fallback);
    }

    public boolean offlineOr(boolean fallback) {
        return offline.orElse(fallback);
    }

    /**
     * True when {@code --force} (or the legacy {@code --rerun}) was set. {@code --force} is the
     * canonical flag; {@code --rerun} is accepted for backward compatibility.
     */
    public boolean rerunOr(boolean fallback) {
        return force.or(() -> rerun).orElse(fallback);
    }

    /**
     * True when {@code --force} (or the legacy {@code --refresh}) was set. {@code --force} is the
     * canonical flag; {@code --refresh} is accepted for backward compatibility.
     */
    public boolean refreshOr(boolean fallback) {
        return force.or(() -> refresh).orElse(fallback);
    }

    /** True when {@code --force} / {@code JK_FORCE} was set for this invocation. */
    public boolean forceOr(boolean fallback) {
        return force.orElse(fallback);
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
