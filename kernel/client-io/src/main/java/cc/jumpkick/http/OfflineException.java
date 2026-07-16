// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.io.IOException;
import java.net.URI;

/**
 * Thrown by {@link Http} when an outbound request is attempted while {@code --offline} (or {@code
 * JK_OFFLINE} / {@code config.offline = true}) is in effect.
 *
 * <p>Surfaces as an {@link IOException} so existing call sites that already handle network failures
 * get a sensible error path without special-casing. Callers that want to substitute cached data on
 * offline should catch this specifically and fall back, rather than swallowing every IOException.
 */
public final class OfflineException extends IOException {

    public OfflineException(URI uri) {
        super("offline: refusing outbound request to " + uri + " (drop --offline / unset JK_OFFLINE to allow network)");
    }
}
