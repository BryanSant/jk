// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.tui.Confirm;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.script.ScriptHeader;
import dev.jkbuild.script.ScriptHeaderParser;
import dev.jkbuild.tool.TrustedSources;
import dev.jkbuild.tool.UrlRewriter;
import dev.jkbuild.util.Hashing;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The client half of a web-URL tool target (docs/tool-targets-plan.md §4.5): the trust gate
 * (§7 — client-side, it owns the TTY, and always against the URL the user typed), the forge
 * rewrite, the download into {@code $JK_CACHE_DIR/tool-src/<sha256(url)>/}, and the fetch of any
 * {@code //SOURCES}/{@code //FILES} siblings relative to the raw URL. The result is a local file
 * the existing script/jar machinery runs or installs unchanged.
 */
final class UrlToolSource {

    private UrlToolSource() {}

    /**
     * The trust gate: {@code null} when the URL may run (trusted, or the user said yes on a TTY);
     * otherwise the exit code to return. An interactive "yes" allows this run only and prints the
     * {@code jk trust add} line for next time.
     */
    static Integer gate(String url, Path stateDir, String verb) throws IOException {
        TrustedSources trust = TrustedSources.load(stateDir);
        if (trust.isTrusted(url)) return null;
        String suggested = TrustedSources.suggestedPrefix(url);
        if (Confirm.isInteractiveTerminal()) {
            boolean yes = Confirm.of("Run code from " + url + "? (source is not trusted)", false)
                    .ask();
            if (!yes) {
                CliOutput.err(verb + ": declined.");
                return Exit.USAGE;
            }
            CliOutput.err("Allowed once. To trust this source permanently: jk trust add " + suggested);
            return null;
        }
        CliOutput.err(verb + ": " + url + " is not a trusted source.\n"
                + "Trust it first: jk trust add " + suggested);
        return Exit.USAGE;
    }

    /**
     * Fetch {@code url} (post-rewrite) into the tool-src cache and return the local file. A cached
     * copy is reused unless {@code refresh}; sibling {@code //SOURCES}/{@code //FILES} of a source
     * script are fetched alongside it, so the local-file pipeline sees the same layout the remote
     * had.
     */
    static Path fetch(String url, Path cacheDir, boolean refresh) throws IOException, InterruptedException {
        String raw = UrlRewriter.rewrite(url);
        URI uri = URI.create(raw);
        Path dir = cacheDir.resolve("tool-src").resolve(Hashing.sha256Hex(raw.getBytes(StandardCharsets.UTF_8)));

        if (!refresh && Files.isDirectory(dir)) {
            List<Path> cached = topLevelFiles(dir);
            for (Path c : cached) {
                if (isRunnable(c.getFileName().toString())) return c;
            }
        }

        Http http = new Http();
        byte[] body = get(http, uri);
        String name = fileName(uri, body);
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.write(file, body);

        // A source script's //SOURCES and //FILES are relative to the script — mirror them
        // from the raw URL's base so compilation sees the layout the remote had.
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".kts")) {
            String text = new String(body, StandardCharsets.UTF_8);
            ScriptHeader header =
                    lower.endsWith(".java") ? ScriptHeaderParser.parse(text) : ScriptHeaderParser.parseKotlin(text);
            List<String> siblings = new ArrayList<>(header.sources());
            for (String spec : header.files()) {
                int eq = spec.indexOf('=');
                siblings.add(eq >= 0 ? spec.substring(eq + 1) : spec);
            }
            for (String rel : siblings) {
                Path target = dir.resolve(rel).normalize();
                if (!target.startsWith(dir)) {
                    throw new IOException("remote script references a path outside its directory: " + rel);
                }
                if (!refresh && Files.isRegularFile(target)) continue;
                URI sibling = uri.resolve(rel);
                Files.createDirectories(target.getParent());
                Files.write(target, get(http, sibling));
            }
        }
        return file;
    }

    private static byte[] get(Http http, URI uri) throws IOException, InterruptedException {
        var response = http.get(uri);
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " fetching " + uri);
        }
        return response.body();
    }

    /** The cache file name: the URL's last path segment, extension sniffed when it has none. */
    private static String fileName(URI uri, byte[] body) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isBlank()) name = "script";
        if (isRunnable(name)) return name;
        // No runnable extension (gist /raw, shorteners): sniff the payload.
        if (body.length >= 2 && body[0] == 'P' && body[1] == 'K') return name + ".jar";
        String text = new String(body, 0, Math.min(body.length, 8192), StandardCharsets.UTF_8);
        if (text.contains("fun main(") || text.contains("@file:")) return name + ".kt";
        return name + ".java";
    }

    private static boolean isRunnable(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".kts") || n.endsWith(".jar");
    }

    private static List<Path> topLevelFiles(Path dir) throws IOException {
        try (var listing = Files.list(dir)) {
            return listing.filter(Files::isRegularFile).sorted().toList();
        }
    }
}
