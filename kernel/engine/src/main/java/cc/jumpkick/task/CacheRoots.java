// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Collects every CAS reference the on-disk roots name, so the sweep can tell the live set from
 * collectible garbage.
 *
 * <p>Roots scanned:
 *
 * <ul>
 *   <li>{@code actions/keys/<key>} — compile manifests. INPUT and OUTPUT lines name shas directly;
 *       CP lines embed shas in the absolute path they hand to javac.
 *   <li>{@code actions/synced/<projectId>} — sync manifests (per-project reachability stamped by
 *       {@code jk sync}).
 *   <li>{@code tools/envs/<bin>/env.json} — tool environments. Their classpath entries are absolute
 *       paths into the CAS.
 * </ul>
 *
 * <p>The scanner is intentionally string-pattern based rather than a strict format parser: any text
 * that <em>looks like</em> a path under the CAS contributes its sha to the reachable set. That
 * tolerates format drift and means a new on-disk root subsystem can opt into being-reachable just
 * by writing absolute CAS paths anywhere it likes.
 */
public final class CacheRoots {

    private CacheRoots() {}

    /**
     * Collect every reachable sha from the on-disk roots beneath the given action + tools
     * directories. The CAS is consulted only to recognise its own path layout — no objects are
     * touched.
     */
    public static Set<String> collect(Cas cas, Path actionsDir, Path toolsDir) throws IOException {
        Set<String> refs = new HashSet<>();
        if (Files.isDirectory(actionsDir.resolve("keys"))) {
            scanTextFilesRecursively(actionsDir.resolve("keys"), cas, refs);
        }
        if (Files.isDirectory(actionsDir.resolve(Sweep.SYNCED_SUBDIR))) {
            scanTextFilesRecursively(actionsDir.resolve(Sweep.SYNCED_SUBDIR), cas, refs);
        }
        if (Files.isDirectory(toolsDir.resolve("envs"))) {
            scanTextFilesRecursively(toolsDir.resolve("envs"), cas, refs);
        }
        // repos/local is a PUBLISH DESTINATION (installLocal / jk publish local), not a derived
        // cache: a freshly published dev artifact is legitimately unreferenced by any action or
        // sync manifest until the first build consumes it, and the sweep must not eat it in that
        // window (it once deleted every just-installed worker jar). Its .sha256 sidecars are
        // therefore roots. Other repos/<name> stores are re-fetchable mirrors and stay sweepable.
        Path localRepo = cas.root().resolve("repos").resolve("local");
        if (Files.isDirectory(localRepo)) {
            try (Stream<Path> stream = Files.walk(localRepo)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(file) || !file.toString().endsWith(".sha256")) continue;
                    try {
                        addExplicitShaTokens(Files.readString(file, StandardCharsets.UTF_8), refs);
                    } catch (IOException ignored) {
                        // unreadable sidecar — its artifact simply isn't protected
                    }
                }
            }
        }
        return refs;
    }

    /**
     * Walk {@code dir}, read every regular file as text, pull explicit sha tokens AND any CAS-style
     * path fragments into {@code refs}.
     */
    private static void scanTextFilesRecursively(Path dir, Cas cas, Set<String> refs) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                String body;
                try {
                    body = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // Skip binaries / unreadable files; if our text roots
                    // ever go binary we'll need a per-source parser anyway.
                    continue;
                }
                addExplicitShaTokens(body, refs);
                addPathEmbeddedShas(body, cas, refs);
            }
        }
    }

    /**
     * Action records emit lines like {@code INPUT <sha> <path>} and {@code REF <sha>} (sync
     * manifests). Pull those direct mentions.
     */
    private static void addExplicitShaTokens(String body, Set<String> refs) {
        // 64-char lowercase hex, surrounded by start-of-line / whitespace /
        // a `sha256:` prefix. Anchor on whitespace either side so we don't
        // catch hex fragments inside longer strings.
        Matcher m = EXPLICIT_SHA.matcher(body);
        while (m.find()) {
            refs.add(m.group(1));
        }
    }

    /**
     * Any absolute path that looks like {@code .../sha256/AA/BB/<rest>} is treated as a reference —
     * covers tool env JSONs, action-record {@code INPUT cp:} lines, and any other writer that stamps
     * absolute CAS paths.
     */
    private static void addPathEmbeddedShas(String body, Cas cas, Set<String> refs) {
        Matcher m = CAS_PATH.matcher(body);
        while (m.find()) {
            cas.hashFromPath(Path.of(m.group())).ifPresent(refs::add);
        }
    }

    /** 64 hex chars, optionally preceded by {@code sha256:}. */
    private static final Pattern EXPLICIT_SHA =
            Pattern.compile("(?:^|[\\s:])(?:sha256:)?([0-9a-f]{64})(?:$|[\\s\\n])", Pattern.MULTILINE);

    /**
     * A path fragment ending in the CAS layout's {@code sha256/AA/BB/<60-hex>} suffix. We anchor on
     * the literal {@code sha256/} segment so the match starts where the directory does — the caller
     * still calls {@link Cas#hashFromPath} to verify the path actually sits under the active CAS
     * root.
     */
    private static final Pattern CAS_PATH =
            Pattern.compile("(?<=[\\s\"'(])/[^\\s\"'\\n]*/sha256/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{60}");
}
