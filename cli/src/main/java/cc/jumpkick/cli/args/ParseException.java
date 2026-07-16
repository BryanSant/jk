// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.args;

/**
 * Raised by {@link ArgParser} when the argument vector doesn't match a command's declared
 * options/parameters. Carries a {@link Kind} and the offending token so the error renderer can
 * produce a cargo-style diagnostic (unrecognized option, missing value, missing required argument,
 * too many arguments) — the same categories the picocli {@code ParameterException} hierarchy gave
 * us.
 */
public final class ParseException extends Exception {

    public enum Kind {
        UNKNOWN_OPTION,
        AMBIGUOUS_OPTION,
        MISSING_VALUE,
        MISSING_REQUIRED,
        TOO_MANY_ARGS,
    }

    private final transient Kind kind;
    private final transient String token;

    public ParseException(Kind kind, String token, String message) {
        super(message);
        this.kind = kind;
        this.token = token;
    }

    public Kind kind() {
        return kind;
    }

    /** The offending token (an option/argument), or the name of the missing parameter. */
    public String token() {
        return token;
    }
}
