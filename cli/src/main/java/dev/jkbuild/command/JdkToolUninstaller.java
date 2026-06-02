// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.jdk.JdkHit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort "good neighbor" delegation for {@code jk jdk uninstall}.
 *
 * <p>When a JDK comes from a tool that manages its own state
 * (SDKMAN, mise, jbang, jenv, asdf, Homebrew), removing the install
 * directory out from under it leaves the tool's manifest / shims /
 * version index out of sync. So we shell out to the owning tool's
 * uninstall command first — non-interactive, with a short timeout —
 * and fall back to the direct {@code rm -rf} purge only when that
 * fails (binary not on PATH, command errored, install dir still
 * present after).
 *
 * <p>{@code intellij} (jk's own {@code ~/.jdks}) and {@code java-home}
 * don't go through here — those are direct deletes. IntelliJ
 * recovers from a missing JDK on its own.
 */
final class JdkToolUninstaller {

    /** How long any one tool command is allowed to run before we abandon it. */
    private static final long TIMEOUT_SECONDS = 30;

    /** Outcome label used by the caller for the "✓ … via <tool>" line. */
    enum Outcome { HANDLED_BY_TOOL, FALL_THROUGH }

    private JdkToolUninstaller() {}

    /**
     * Attempt to uninstall {@code hit} via its owning tool. Returns
     * {@link Outcome#HANDLED_BY_TOOL} when the tool ran cleanly and the
     * install directory is gone afterwards; {@link Outcome#FALL_THROUGH}
     * otherwise (caller should run the direct {@code purge} fallback).
     */
    static Outcome tryUninstall(JdkHit hit, String identifier) {
        List<String> command = commandFor(hit, identifier);
        if (command == null) return Outcome.FALL_THROUGH;
        if (!runQuietly(command)) return Outcome.FALL_THROUGH;
        // Some tools say "OK" without actually deleting (e.g. wrong identifier
        // shape, force flag missing). Verify the install dir is gone before
        // claiming success.
        return Files.exists(hit.home()) ? Outcome.FALL_THROUGH : Outcome.HANDLED_BY_TOOL;
    }

    /**
     * The non-interactive command line for {@code hit.source()}'s owning
     * tool. Returns {@code null} when we don't have a recipe for this
     * source — the caller treats that the same as "tool failed" and
     * falls back to the direct delete.
     */
    private static List<String> commandFor(JdkHit hit, String identifier) {
        return switch (hit.source()) {
            // `sdk` is a shell function from sdkman-init.sh, not a binary —
            // source the init explicitly so this works under cron / non-login
            // shells too. SDKMAN's uninstall is non-interactive when given
            // both candidate and version.
            case "sdkman" -> List.of("bash", "-c",
                    "source \"$HOME/.sdkman/bin/sdkman-init.sh\" && "
                            + "sdk uninstall java " + shellQuote(identifier));
            case "mise" -> List.of("mise", "uninstall", "--yes", "java@" + identifier);
            case "jbang" -> List.of("jbang", "jdk", "uninstall", identifier);
            // jenv tracks JDKs but doesn't own their files — `remove` just
            // unregisters the alias. Pair it with a purge so the on-disk
            // install actually goes away too. Returning the command here
            // gets jenv's manifest cleaned up; the FALL_THROUGH check below
            // will see the dir still exists and trigger purge — exactly
            // what we want.
            case "jenv" -> List.of("jenv", "remove", identifier);
            case "asdf" -> List.of("asdf", "uninstall", "java", identifier);
            // Homebrew installs land in {Cellar}/<formula>/<version>; the
            // formula name (e.g. "openjdk@21") is what `brew uninstall`
            // takes, not the install-folder name.
            case "homebrew" -> {
                String formula = homebrewFormulaFor(hit.home());
                yield formula == null ? null : List.of("brew", "uninstall", formula);
            }
            default -> null;
        };
    }

    /**
     * Walk up from a Homebrew JDK home to find the {@code Cellar/<formula>}
     * segment and return the formula name. Returns {@code null} when the
     * path doesn't look like a Cellar install (in which case the caller
     * falls back to the direct purge).
     */
    private static String homebrewFormulaFor(Path home) {
        Path p = home;
        while (p != null && p.getParent() != null) {
            Path parent = p.getParent();
            if (parent.getFileName() != null
                    && "Cellar".equals(parent.getFileName().toString())) {
                return p.getFileName() != null ? p.getFileName().toString() : null;
            }
            p = parent;
        }
        return null;
    }

    /**
     * Fire-and-wait subprocess with stdio dropped — we don't want the tool's
     * progress chatter mixed into jk's own spinner output. Bounded by
     * {@link #TIMEOUT_SECONDS}; anything that hangs longer than that is
     * killed and treated as a failure.
     */
    private static boolean runQuietly(List<String> command) {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // Close stdin immediately so any unexpected prompt sees EOF
            // (most tools default to "no" on EOF, which is fine here since
            // we still verify via the dir-exists check afterwards).
            p.getOutputStream().close();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            // Most-common case: the tool's binary isn't on PATH.
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Single-quote a token for {@code bash -c} interpolation. */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
