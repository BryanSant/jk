// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.forge;

/**
 * The device-code grant's first-response payload (RFC 8628 §3.2). Handed to the caller's prompt
 * callback so the UI can show {@link #userCode()} and point the user at {@link #verificationUri()}
 * (or the pre-filled {@link #verificationUriComplete()} when the provider supplies one).
 */
public record DeviceCode(
        String deviceCode,
        String userCode,
        String verificationUri,
        String verificationUriComplete, // may be null; provider-optional
        int interval,
        int expiresIn) {}
