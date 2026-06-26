// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Environment plumbing for {@code jk mvn} / {@code jk gradle} passthroughs.
 *
 * <p>Scrubs the env vars that would otherwise silently change Maven/Gradle
 * behavior under the user's nose (PRD §23.5):
 * {@code JAVA_TOOL_OPTIONS}, {@code _JAVA_OPTIONS}, {@code KOTLIN_HOME},
 * {@code MAVEN_OPTS}, {@code GRADLE_OPTS}. Inherits the rest — passthrough
 * isn't a hermetic build, so {@code PATH} / {@code HOME} / shell vars stay.
 *
 * <p>When a project JDK is provided, sets {@code JAVA_HOME} and prepends
 * {@code <jdk>/bin} to {@code PATH} so the child finds it first.
 */
public final class PassthroughEnv {

    private static final String[] STRIPPED = {
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "KOTLIN_HOME",
            "MAVEN_OPTS",
            "GRADLE_OPTS",
    };

    private PassthroughEnv() {}

    /** Mutate {@code env} in-place: strip override vars, optionally set JAVA_HOME. */
    public static void apply(Map<String, String> env, Path javaHome) {
        for (String key : STRIPPED) {
            env.remove(key);
        }
        if (javaHome != null) {
            String home = javaHome.toAbsolutePath().toString();
            env.put("JAVA_HOME", home);
            String binDir = javaHome.resolve("bin").toAbsolutePath().toString();
            String pathKey = pathKey(env);
            String existing = env.getOrDefault(pathKey, "");
            String separator = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ";" : ":";
            env.put(pathKey, existing.isEmpty() ? binDir : binDir + separator + existing);
        }
    }

    /** Windows env vars are case-insensitive; PATH may arrive as {@code Path}. */
    private static String pathKey(Map<String, String> env) {
        if (env.containsKey("PATH")) return "PATH";
        for (String k : env.keySet()) {
            if (k.equalsIgnoreCase("PATH")) return k;
        }
        return "PATH";
    }

}
