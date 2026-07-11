// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.util.ArrayList;
import java.util.List;

/**
 * jk's default javac lint policy. By default jk compiles with {@code -Xlint:deprecation,unchecked}
 * so deprecation/unchecked warnings are surfaced (mirrors the flags jk's own Gradle build uses).
 * Users who don't want the noise set {@code [build] lint = false} in {@code jk.toml}.
 *
 * <p>The flags only make javac <em>emit</em> the warnings — they're surfaced through the warn
 * channel and never fail the build. A flag the user adds via a profile's {@code javac} args still
 * wins (it's appended after these).
 */
public final class JavacLint {

    private JavacLint() {}

    /** The default lint flags injected when {@code [build] lint} is on. */
    public static final List<String> DEFAULT_ARGS = List.of("-Xlint:deprecation,unchecked");

    /**
     * The effective javac args: jk's default lint flags (when {@code lintEnabled}) followed by the
     * user's own {@code javac} args.
     */
    public static List<String> effectiveArgs(boolean lintEnabled, List<String> userArgs) {
        return effectiveArgs(lintEnabled, List.of(), userArgs);
    }

    /**
     * The effective javac args: jk's default lint flags (when {@code lintEnabled}), the installed
     * plugins' contributed args (e.g. the spring-boot manifest's {@code -parameters} — the
     * framework reflects on constructor/handler parameter names), then the user's own {@code
     * javac} args. A contributed arg the user already passes is not duplicated (user wins on
     * position).
     */
    public static List<String> effectiveArgs(boolean lintEnabled, List<String> contributedArgs, List<String> userArgs) {
        List<String> user = userArgs == null ? List.of() : userArgs;
        List<String> contributed = contributedArgs == null ? List.of() : contributedArgs;
        List<String> out = new ArrayList<>(DEFAULT_ARGS.size() + contributed.size() + user.size());
        if (lintEnabled) out.addAll(DEFAULT_ARGS);
        for (String arg : contributed) {
            if (!user.contains(arg) && !out.contains(arg)) out.add(arg);
        }
        out.addAll(user);
        return List.copyOf(out);
    }
}
