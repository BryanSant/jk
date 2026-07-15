// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.repo;

/** Raised when a POM cannot be parsed. */
public final class PomParseException extends RuntimeException {

    public PomParseException(String message) {
        super(message);
    }

    public PomParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
