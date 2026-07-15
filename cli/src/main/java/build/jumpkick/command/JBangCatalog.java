// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.http.Http;
import build.jumpkick.plugin.protocol.Ndjson;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JBang catalog resolution for {@code alias@catalog} targets (docs/tool-targets-plan.md §6,
 * matching JBang's documented grammar):
 *
 * <ul>
 *   <li>{@code hello@user} → {@code jbang-catalog.json} in the {@code user/jbang-catalog} repo
 *       (GitHub, then GitLab, then Bitbucket).
 *   <li>{@code hello@user/repo[/branch][~path]} — explicit repo, optional branch and subdirectory.
 *   <li>{@code hello@host.tld[/path]} — an HTTPS catalog at
 *       {@code https://host.tld/path/jbang-catalog.json}.
 * </ul>
 *
 * <p>The catalog file itself is data; the trust gate (§7) runs on the catalog's page-style origin
 * before any script it names is fetched or run. Relative {@code script-ref}s resolve against the
 * catalog's raw base and inherit that gate; absolute URLs and coordinates re-enter the normal
 * target flow. Alias {@code dependencies} join the script's resolution and {@code java-options}
 * ride the exec (or the installed launcher's exec line).
 */
final class JBangCatalog {

    /** A located catalog: where it came from (for trust + relative refs) and the alias entry. */
    record Resolved(
            String pageOrigin,
            URI rawBase,
            String scriptRef,
            List<String> arguments,
            List<String> dependencies,
            List<String> javaOptions) {}

    private JBangCatalog() {}

    /** Resolve {@code alias@rest}; throws {@link IOException} with a user-ready message. */
    static Resolved resolve(String target, Http http) throws IOException, InterruptedException {
        int at = target.indexOf('@');
        String alias = target.substring(0, at);
        String rest = target.substring(at + 1);

        String path = "";
        int tilde = rest.indexOf('~');
        if (tilde >= 0) {
            path = rest.substring(tilde + 1);
            rest = rest.substring(0, tilde);
        }

        List<Candidate> candidates = candidatesFor(rest, path);
        IOException lastFailure = null;
        for (Candidate c : candidates) {
            try {
                var response = http.get(URI.create(c.catalogUrl()));
                if (response.statusCode() != 200) continue;
                // Ndjson's lookups expect the wire's compact form; catalogs are
                // pretty-printed by humans — normalize before parsing.
                String json = compact(new String(response.body(), StandardCharsets.UTF_8));
                return toResolved(target, alias, c, json);
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        if (lastFailure != null) throw lastFailure;
        throw new IOException("no jbang-catalog.json found for `" + rest + "` (tried "
                + candidates.stream().map(Candidate::catalogUrl).toList() + ")");
    }

    private static Resolved toResolved(String target, String alias, Candidate c, String json) throws IOException {
        String aliases = Ndjson.nested(json, "aliases");
        String entry = aliases != null ? Ndjson.nested(aliases, alias) : null;
        if (entry == null) {
            throw new IOException("catalog " + c.catalogUrl() + " has no alias `" + alias + "`");
        }
        String scriptRef = Ndjson.str(entry, "script-ref");
        if (scriptRef == null || scriptRef.isBlank()) {
            throw new IOException("alias `" + alias + "` in " + c.catalogUrl() + " has no script-ref");
        }
        String base = c.catalogUrl().substring(0, c.catalogUrl().lastIndexOf('/') + 1);
        return new Resolved(
                c.pageOrigin(),
                URI.create(base),
                scriptRef,
                Ndjson.strArray(entry, "arguments"),
                Ndjson.strArray(entry, "dependencies"),
                Ndjson.strArray(entry, "java-options"));
    }

    /** Strip whitespace outside string literals so Ndjson's compact-form lookups match. */
    static String compact(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                sb.append(ch);
                if (ch == '\\' && i + 1 < json.length()) sb.append(json.charAt(++i));
                else if (ch == '"') inString = false;
            } else if (ch == '"') {
                inString = true;
                sb.append(ch);
            } else if (!Character.isWhitespace(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private record Candidate(String catalogUrl, String pageOrigin) {}

    private static List<Candidate> candidatesFor(String rest, String path) {
        String sub = path.isEmpty() ? "" : path + "/";
        String[] parts = rest.split("/");
        // A dot in the first segment = a hostname catalog, not a forge username.
        if (parts[0].contains(".")) {
            String tail = rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest;
            String scheme = parts[0].startsWith("127.0.0.1") || parts[0].startsWith("localhost")
                    ? "http"
                    : "https";
            return List.of(new Candidate(
                    scheme + "://" + tail + "/" + sub + "jbang-catalog.json",
                    scheme + "://" + parts[0] + "/" + (parts.length > 1 ? parts[1] + "/" : "")));
        }
        String user = parts[0];
        String repo = parts.length > 1 ? parts[1] : "jbang-catalog";
        String branch = parts.length > 2 ? parts[2] : "HEAD";
        return List.of(
                new Candidate(
                        "https://raw.githubusercontent.com/" + user + "/" + repo + "/" + branch + "/" + sub
                                + "jbang-catalog.json",
                        "https://github.com/" + user + "/"),
                new Candidate(
                        "https://gitlab.com/" + user + "/" + repo + "/-/raw/" + branch + "/" + sub
                                + "jbang-catalog.json",
                        "https://gitlab.com/" + user + "/"),
                new Candidate(
                        "https://bitbucket.org/" + user + "/" + repo + "/raw/" + branch + "/" + sub
                                + "jbang-catalog.json",
                        "https://bitbucket.org/" + user + "/"));
    }
}
