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
        return effectiveArgs(lintEnabled, false, userArgs);
    }

    /**
     * The effective javac args: jk's default lint flags (when {@code lintEnabled}), {@code
     * -parameters} (when {@code parametersDefault} — Spring Boot projects, where the framework
     * reflects on constructor/handler parameter names), then the user's own {@code javac} args.
     */
    public static List<String> effectiveArgs(boolean lintEnabled, boolean parametersDefault, List<String> userArgs) {
        List<String> user = userArgs == null ? List.of() : userArgs;
        List<String> out = new ArrayList<>(DEFAULT_ARGS.size() + user.size() + 1);
        if (lintEnabled) out.addAll(DEFAULT_ARGS);
        if (parametersDefault && !user.contains("-parameters")) out.add("-parameters");
        out.addAll(user);
        return List.copyOf(out);
    }
}
