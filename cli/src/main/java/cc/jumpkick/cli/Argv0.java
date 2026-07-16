// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import java.util.Locale;

/**
 * The program name this process was invoked as — argv[0]'s basename, with a Windows {@code .exe}
 * suffix stripped.
 *
 * <p>{@code jkx} is a hardlink (or link-shaped fallback) to the {@code jk} binary in {@code
 * $JK_BIN_DIR}; {@link Jk#main} dispatches on this name to expand {@code jkx …} into {@code jk tool
 * run …} (uvx/npx muscle memory, PRD §20.3) without shipping a second binary.
 *
 * <p>Inside the native image the real argv[0] comes from {@code
 * ProcessProperties.getArgumentVectorProgramName()}, which reports the literal invocation name —
 * correct for hardlinks, symlinks, and {@code exec -a} alike. On a JVM there is no argv[0]; the
 * executable path from {@link ProcessHandle} stands in (it resolves symlinks, but the JVM dist
 * installs the shim-script form of {@code jkx}, which never reaches this code as {@code jkx}
 * anyway). The GraalVM API class is referenced only from a nested holder so JVM mode never loads
 * it — the dependency is {@code compileOnly}.
 */
final class Argv0 {

    /** Test seam: overrides the detected program name. Hidden, never documented. */
    static final String OVERRIDE_PROPERTY = "jk.argv0";

    private Argv0() {}

    /** The invocation basename (e.g. {@code "jk"}, {@code "jkx"}), or null when undeterminable. */
    static String programName() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null) return baseName(override);
        try {
            if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
                return baseName(NativeArgv0.get());
            }
            return ProcessHandle.current().info().command().map(Argv0::baseName).orElse(null);
        } catch (RuntimeException | LinkageError e) {
            // Never let program-name sniffing break the CLI — no name, no dispatch.
            return null;
        }
    }

    /** Basename of {@code path}, lowercased for comparison, {@code .exe} stripped. */
    static String baseName(String path) {
        if (path == null || path.isBlank()) return null;
        String name = path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - ".exe".length());
        return name.isEmpty() ? null : name;
    }

    /** Holder so the GraalVM SDK class only loads inside the image (compileOnly on the JVM). */
    private static final class NativeArgv0 {
        static String get() {
            return org.graalvm.nativeimage.ProcessProperties.getArgumentVectorProgramName();
        }
    }
}
