// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The build invocation spec written by the CLI into a temp file and read by
 * the Host on startup. A simple line-oriented format: {@code KEY value},
 * one entry per line (blank lines and lines starting with {@code #} ignored).
 *
 * <p>Required keys:
 * <pre>
 *   VERB      build | compile | test | …
 *   DIR       absolute path to the project / workspace root
 *   CACHE     absolute path to $JK_CACHE_DIR
 * </pre>
 *
 * <p>Optional keys (all paths absolute):
 * <pre>
 *   LOCK_FILE  jk.lock path (defaults to DIR/jk.lock)
 *   JDKS_DIR   JDK install root override
 *   PROFILE    build profile name
 *   WORKERS    parallel test-runner JVM count (integer, default 1)
 *   SKIP_TESTS true|false
 *   VERBOSE    true|false
 *   JSON_OUT   true|false
 * </pre>
 */
public record HostInvocation(
        String verb,
        Path dir,
        Path cache,
        Path lockFile,
        Path jdksDir,
        String profile,
        int workers,
        boolean skipTests,
        boolean verbose,
        boolean jsonOut) {

    /** Parse a spec file produced by the CLI. */
    public static HostInvocation read(Path specFile) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : Files.readAllLines(specFile, StandardCharsets.UTF_8)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int sp = line.indexOf(' ');
            if (sp < 0) continue;
            m.put(line.substring(0, sp), line.substring(sp + 1).strip());
        }
        String verb  = require(m, "VERB");
        Path dir     = Path.of(require(m, "DIR"));
        Path cache   = Path.of(require(m, "CACHE"));
        Path lock    = m.containsKey("LOCK_FILE") ? Path.of(m.get("LOCK_FILE")) : dir.resolve("jk.lock");
        Path jdksDir = m.containsKey("JDKS_DIR") ? Path.of(m.get("JDKS_DIR")) : null;
        return new HostInvocation(
                verb, dir, cache, lock, jdksDir,
                m.get("PROFILE"),
                Integer.parseInt(m.getOrDefault("WORKERS", "1")),
                "true".equalsIgnoreCase(m.getOrDefault("SKIP_TESTS", "false")),
                "true".equalsIgnoreCase(m.getOrDefault("VERBOSE", "false")),
                "true".equalsIgnoreCase(m.getOrDefault("JSON_OUT", "false")));
    }

    /** Write a spec file and return its path; caller is responsible for deletion. */
    public static Path write(HostInvocation inv) throws IOException {
        var lines = new java.util.ArrayList<String>();
        lines.add("VERB "     + inv.verb());
        lines.add("DIR "      + inv.dir().toAbsolutePath());
        lines.add("CACHE "    + inv.cache().toAbsolutePath());
        lines.add("LOCK_FILE "+ inv.lockFile().toAbsolutePath());
        if (inv.jdksDir() != null) lines.add("JDKS_DIR " + inv.jdksDir().toAbsolutePath());
        if (inv.profile() != null) lines.add("PROFILE "  + inv.profile());
        lines.add("WORKERS "    + inv.workers());
        lines.add("SKIP_TESTS " + inv.skipTests());
        lines.add("VERBOSE "    + inv.verbose());
        lines.add("JSON_OUT "   + inv.jsonOut());
        Path spec = Files.createTempFile("jk-host-", ".spec");
        Files.write(spec, lines, StandardCharsets.UTF_8);
        return spec;
    }

    private static String require(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null) throw new IllegalArgumentException("spec missing required key: " + key);
        return v;
    }
}
