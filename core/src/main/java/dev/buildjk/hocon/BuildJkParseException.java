// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

/** Raised when a {@code build.jk} file cannot be parsed or fails minimal validation. */
public final class BuildJkParseException extends RuntimeException {

    public BuildJkParseException(String message) {
        super(message);
    }

    public BuildJkParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
