// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The trusted-sources store gating remote code for {@code jk tool run|install}
 * (docs/tool-targets-plan.md §7) — a list of URL prefixes in {@code
 * $JK_STATE_DIR/trusted-sources.toml}, JBang-style base-URL matching: trusting {@code
 * https://github.com/acme/} covers everything under it.
 *
 * <p>Matching normalizes the scheme and host to lowercase (paths stay case-sensitive) and checks a
 * plain prefix. The check runs <em>client-side, before</em> any engine request or download — and
 * against the URL the user typed, not the raw-content rewrite, so the trusted prefix reads like
 * the address bar.
 */
public final class TrustedSources {

    private static final String FILE_NAME = "trusted-sources.toml";
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    private final Path file;
    private final List<String> prefixes;

    private TrustedSources(Path file, List<String> prefixes) {
        this.file = file;
        this.prefixes = prefixes;
    }

    public static TrustedSources load(Path stateDir) throws IOException {
        Path file = stateDir.resolve(FILE_NAME);
        List<String> prefixes = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            // Line reader, not tomlj: this gate runs client-side before every tool fetch
            // (thin-client plan — the trust decision must not require a TOML parser), and
            // the file is jk-managed: `sources = [` … one quoted prefix per line … `]`,
            // with the single-line array form tolerated for hand edits.
            boolean inSources = false;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!inSources) {
                    if (!line.startsWith("sources")) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    line = line.substring(eq + 1).strip();
                    if (line.startsWith("[")) line = line.substring(1);
                    inSources = true;
                }
                // The array closes at a `]` outside quotes (IPv6 prefixes carry `]` inside).
                int close = line.indexOf(']', line.lastIndexOf('"') + 1);
                boolean closes = close >= 0;
                if (closes) line = line.substring(0, close);
                var m = QUOTED.matcher(line);
                while (m.find()) {
                    prefixes.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
                }
                if (closes) break;
            }
        }
        return new TrustedSources(file, prefixes);
    }

    public List<String> list() {
        return List.copyOf(prefixes);
    }

    /** True when {@code url} falls under any trusted prefix. */
    public boolean isTrusted(String url) {
        String normalized = normalize(url);
        for (String prefix : prefixes) {
            if (normalized.startsWith(normalize(prefix))) return true;
        }
        return false;
    }

    /** Add {@code prefix}; returns false when it was already present. Persists on change. */
    public boolean add(String prefix) throws IOException {
        String p = prefix.trim();
        if (prefixes.stream().anyMatch(e -> normalize(e).equals(normalize(p)))) return false;
        prefixes.add(p);
        save();
        return true;
    }

    /** Remove {@code prefix}; returns false when it wasn't present. Persists on change. */
    public boolean remove(String prefix) throws IOException {
        boolean removed = prefixes.removeIf(e -> normalize(e).equals(normalize(prefix)));
        if (removed) save();
        return removed;
    }

    /**
     * The prefix to suggest for {@code url} in prompts and errors: scheme, host, and the first
     * path segment — {@code https://github.com/acme/widgets/blob/…} → {@code
     * https://github.com/acme/}.
     */
    public static String suggestedPrefix(String url) {
        try {
            URI uri = URI.create(url.trim());
            String path = uri.getPath() == null ? "" : uri.getPath();
            String[] segments = path.split("/");
            String first = segments.length > 1 ? segments[1] : "";
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host + port + "/"
                    + (first.isEmpty() ? "" : first + "/");
        } catch (RuntimeException e) {
            return url;
        }
    }

    /**
     * Parse JBang's {@code ~/.jbang/trusted-sources.json} — a JSON string array that may carry
     * {@code //} comment lines — into importable prefixes.
     */
    public static List<String> parseJBang(String json) {
        List<String> out = new ArrayList<>();
        for (String line : json.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith("//")) continue;
            Matcher m = QUOTED.matcher(stripped);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# URL prefixes allowed to supply runnable code for `jk tool run|install`.\n");
        sb.append("# Managed by `jk trust add|remove|import`; hand edits are fine.\n");
        sb.append("sources = [\n");
        for (String p : prefixes) {
            sb.append("  \"").append(p.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\n");
        }
        sb.append("]\n");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Lowercase the scheme and host; leave the path alone. Unparseable input returns as-is. */
    static String normalize(String url) {
        String u = url.trim();
        int schemeEnd = u.indexOf("://");
        if (schemeEnd < 0) return u;
        int pathStart = u.indexOf('/', schemeEnd + 3);
        String schemeHost = pathStart < 0 ? u : u.substring(0, pathStart);
        String rest = pathStart < 0 ? "" : u.substring(pathStart);
        return schemeHost.toLowerCase(Locale.ROOT) + rest;
    }
}
