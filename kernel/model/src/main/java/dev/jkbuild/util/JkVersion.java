// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.util;

/** The jk release version, in a non-view module so any layer can read it. */
public final class JkVersion {
    private JkVersion() {}

    /** Current jk version (e.g. used to key the action cache and {@code --version}). */
    public static final String VERSION = "0.1.0-SNAPSHOT";
}
