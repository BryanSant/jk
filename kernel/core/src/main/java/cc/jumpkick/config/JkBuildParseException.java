// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

/** Raised when a {@code jk.toml} file cannot be parsed or fails minimal validation. */
public final class JkBuildParseException extends RuntimeException {

    public JkBuildParseException(String message) {
        super(message);
    }

    public JkBuildParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
