// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.credential;

/**
 * A credential for an artifact repository, resolved by the credential chain (see
 * docs/artifact-repos.md). A pure data type — rendering to an HTTP {@code Authorization} header or
 * to a cloud signing key lives with the transport in {@code :io}, so {@code :core} stays free of
 * HTTP/signing deps.
 *
 * <p>Step 1 covers the HTTP cases ({@link Anonymous}, {@link Basic}, {@link Bearer}); object-store
 * credential shapes (AWS SigV4 keys, Azure SharedKey) are added with their transports.
 */
public sealed interface RepoCredential permits RepoCredential.Anonymous, RepoCredential.Basic, RepoCredential.Bearer {

    /** No authentication — public repositories. */
    record Anonymous() implements RepoCredential {}

    /** HTTP Basic: username + password (or username + token-as-password). */
    record Basic(String username, String password) implements RepoCredential {
        public Basic {
            if (username == null) throw new IllegalArgumentException("username");
            if (password == null) password = "";
        }
    }

    /** HTTP Bearer: a single opaque token. */
    record Bearer(String token) implements RepoCredential {
        public Bearer {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("bearer token must not be blank");
            }
        }
    }

    /** Shared singleton for the no-auth case. */
    RepoCredential ANONYMOUS = new Anonymous();

    /** True when this credential carries no secret (anonymous access). */
    default boolean isAnonymous() {
        return this instanceof Anonymous;
    }
}
