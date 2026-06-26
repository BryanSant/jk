// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

/**
 * Minimal POSIX single-quote escaping for shell values. Wraps in single quotes and escapes embedded
 * single quotes via {@code '\''}. Used by Bash, Zsh, and Fish — PowerShell uses a different scheme
 * (see {@link PwshShell}).
 */
final class PosixQuote {

    private PosixQuote() {}

    /** Quote a value so the shell will pass it through verbatim. */
    static String quote(String value) {
        if (!needsQuoting(value)) return value;
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean needsQuoting(String value) {
        if (value.isEmpty()) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Only ASCII identifier-ish chars + path-y chars pass through unquoted.
            if (!(Character.isLetterOrDigit(c)
                    || c == '_'
                    || c == '-'
                    || c == '.'
                    || c == '/'
                    || c == ':'
                    || c == '+')) {
                return true;
            }
        }
        return false;
    }
}
