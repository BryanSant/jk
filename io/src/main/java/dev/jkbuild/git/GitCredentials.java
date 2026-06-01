// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git;

import org.eclipse.jgit.transport.CredentialsProvider;

/**
 * Supplies a jgit {@link CredentialsProvider} for a remote, so {@link GitFetcher}
 * can clone/fetch private repositories. Injected (rather than hard-wired to the
 * forge layer) to keep {@code GitFetcher} decoupled and testable;
 * {@link dev.jkbuild.git.ForgeGitCredentials} is the production implementation,
 * which reuses {@code ForgeAuth}.
 */
@FunctionalInterface
public interface GitCredentials {

    /**
     * A credentials provider for {@code remoteUrl}, or {@code null} for
     * anonymous access (public HTTPS, or SSH where the agent/keys handle auth).
     */
    CredentialsProvider forRemote(String remoteUrl);

    /** Anonymous — no provider for any remote (the historical default). */
    GitCredentials NONE = remoteUrl -> null;
}
