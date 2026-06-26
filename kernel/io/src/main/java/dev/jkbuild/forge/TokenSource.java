// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.forge;

/**
 * Where a resolved token came from. Callers branch on this — notably, a {@code 401} should only
 * clear jk's stored credential when the token was read from {@link #STORE}; a rejected {@link
 * #NATIVE_CLI} / env token is the user's to fix, so we surface that instead of silently deleting
 * our own (unrelated) state.
 */
public enum TokenSource {
    /** jk's own {@code JK_<KIND>_TOKEN} override. */
    JK_ENV,
    /** An ecosystem-native variable ({@code GH_TOKEN}, {@code GITLAB_TOKEN}, …). */
    NATIVE_ENV,
    /** Piggybacked from a native CLI ({@code gh auth token}). */
    NATIVE_CLI,
    /** jk's stored per-host credential. */
    STORE,
    /** Freshly minted by the interactive device flow. */
    DEVICE_FLOW,
    /** Pasted by the user (personal access token / app password). */
    PAT,
}
