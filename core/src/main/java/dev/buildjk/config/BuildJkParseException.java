// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.config;

/** Raised when a {@code jk.toml} file cannot be parsed or fails minimal validation. */
public final class BuildJkParseException extends RuntimeException {

    public BuildJkParseException(String message) {
        super(message);
    }

    public BuildJkParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
