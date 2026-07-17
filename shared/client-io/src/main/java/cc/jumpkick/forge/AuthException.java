// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

/** Unchecked failure during forge authentication (login / token resolution). */
public final class AuthException extends RuntimeException {
    public AuthException(String message) {
        super(message);
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
