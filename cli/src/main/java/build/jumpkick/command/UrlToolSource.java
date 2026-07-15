// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.tui.Confirm;
import build.jumpkick.http.Http;
import build.jumpkick.model.command.Exit;
import build.jumpkick.script.ScriptHeader;
import build.jumpkick.script.ScriptHeaderParser;
import build.jumpkick.tool.TrustedSources;
import build.jumpkick.tool.UrlRewriter;
import build.jumpkick.util.Hashing;
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

    private static final java.util.regex.Pattern GIST_PAGE =
            java.util.regex.Pattern.compile("https://gist\\.github\\.com/([^/]+)/([0-9a-fA-F]+)/?");
    private static final java.util.regex.Pattern GIST_RAW_URL =
            java.util.regex.Pattern.compile("\"raw_url\":\"([^\"]+)\"");

    private UrlToolSource() {}

    /**
     * The trust gate: {@code null} when the URL may run (trusted, or the user said yes on a TTY);
     * otherwise the exit code to return. An interactive "yes" allows this run only and prints the
     * {@code jk trust add} line for next time.
     */
    static Integer gate(String url, Path stateDir, String command) throws IOException {
        TrustedSources trust = TrustedSources.load(stateDir);
        if (trust.isTrusted(url)) return null;
        String suggested = TrustedSources.suggestedPrefix(url);
        if (Confirm.isInteractiveTerminal()) {
            boolean yes = Confirm.of("Run code from " + url + "? (source is not trusted)", false)
                    .ask();
            if (!yes) {
                CliOutput.err(command + ": declined.");
                return Exit.USAGE;
            }
            CliOutput.err("Allowed once. To trust this source permanently: jk trust add " + suggested);
            return null;
        }
        CliOutput.err(command + ": " + url + " is not a trusted source.\n"
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
        java.util.regex.Matcher gist = GIST_PAGE.matcher(url.trim());
        if (gist.matches()) {
            return fetchGist(gist.group(2), url.trim(), cacheDir, refresh);
        }
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

    /**
     * The https page-style form of a git URL for trust matching: {@code git@github.com:acme/x.git}
     * and {@code gh:acme/x} both gate as {@code https://github.com/acme/x} — the prefix the user
     * would trust reads like the address bar regardless of transport.
     */
    static String gitTrustUrl(String canonicalGitUrl) {
        try {
            URI uri = URI.create(canonicalGitUrl);
            if (uri.getHost() == null) return canonicalGitUrl;
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
            return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + path;
        } catch (RuntimeException e) {
            return canonicalGitUrl;
        }
    }

    /**
     * A gist page URL: ask the gist API for every file (multi-file gists!) and mirror them into
     * one cache dir. Entry pick: {@code main.java} → the single runnable file → the first
     * runnable alphabetically.
     */
    private static Path fetchGist(String id, String pageUrl, Path cacheDir, boolean refresh)
            throws IOException, InterruptedException {
        Path dir = cacheDir.resolve("tool-src").resolve(Hashing.sha256Hex(pageUrl.getBytes(StandardCharsets.UTF_8)));
        if (!refresh && Files.isDirectory(dir)) {
            Path cached = pickGistEntry(dir);
            if (cached != null) return cached;
        }
        Http http = new Http();
        String api = new String(get(http, URI.create("https://api.github.com/gists/" + id)), StandardCharsets.UTF_8);
        Files.createDirectories(dir);
        var m = GIST_RAW_URL.matcher(JBangCatalog.compact(api));
        int count = 0;
        while (m.find()) {
            String rawUrl = m.group(1);
            String name = rawUrl.substring(rawUrl.lastIndexOf('/') + 1);
            Files.write(dir.resolve(name), get(http, URI.create(rawUrl)));
            count++;
        }
        if (count == 0) throw new IOException("gist " + id + " has no files (or the API response was unreadable)");
        Path entry = pickGistEntry(dir);
        if (entry == null) throw new IOException("gist " + id + " has no runnable file (.java/.kt/.kts/.jar)");
        return entry;
    }

    private static Path pickGistEntry(Path dir) throws IOException {
        List<Path> runnable = topLevelFiles(dir).stream()
                .filter(p -> isRunnable(p.getFileName().toString()))
                .toList();
        if (runnable.isEmpty()) return null;
        for (Path p : runnable) {
            if (p.getFileName().toString().equalsIgnoreCase("main.java")) return p;
        }
        return runnable.get(0);
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
