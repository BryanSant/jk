// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

/** A token plus where it came from. {@link #value()} is never logged. */
public record ResolvedToken(String value, TokenSource source) {}
