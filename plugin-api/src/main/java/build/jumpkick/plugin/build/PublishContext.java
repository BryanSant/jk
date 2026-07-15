// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import java.util.Optional;

/**
 * The {@link PublishExtension} surface: signs and publishes the finished module's artifacts. Adds
 * resolved-secret access (signing credentials) to the shared terminal read surface. Execution wired
 * in Stream 6.
 */
public interface PublishContext extends TerminalContext {

    /** A resolved {@code env:}-indirected secret (signing credentials); never echoed back. */
    Optional<String> secret(String key);
}
