// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model.command;

/**
 * jk's process exit-code vocabulary — the single documented source of truth so command bodies use a
 * self-describing name instead of a bare integer whose meaning lived only in a {@code // EX_USAGE}
 * comment. Values follow {@code sysexits.h} where one applies; {@code 0}/{@code 1}/{@code 2} keep
 * their conventional/jk meanings.
 *
 * <p>Adoption is gradual: {@code 0} and {@code 1} are universally understood and often stay bare;
 * the higher sysexits codes are the ones a name genuinely clarifies.
 */
public final class Exit {

    private Exit() {}

    /** Success. */
    public static final int SUCCESS = 0;

    /** General runtime failure (a build/operation ran but did not succeed). */
    public static final int FAILURE = 1;

    /** Bad project/config input: no {@code jk.toml}/{@code jk.lock}, or an invalid argument value. */
    public static final int CONFIG = 2;

    /** {@code EX_USAGE}: the command line itself was wrong (missing/unknown args). */
    public static final int USAGE = 64;

    /** {@code EX_DATAERR}: input data was present but malformed (e.g. no main class to run). */
    public static final int DATA_ERR = 65;

    /** {@code EX_NOINPUT}: a required input file is missing or unreadable. */
    public static final int NO_INPUT = 66;

    /** {@code EX_SOFTWARE}: an internal error the user cannot act on. */
    public static final int SOFTWARE = 70;

    /** {@code EX_CANTCREAT}: an output file could not be created (would overwrite, permissions). */
    public static final int CANT_CREATE = 73;
}
