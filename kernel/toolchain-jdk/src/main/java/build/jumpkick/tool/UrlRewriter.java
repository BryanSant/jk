// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites forge page URLs to their raw-content endpoints (docs/tool-targets-plan.md §4.5,
 * JBang-compatible): users paste the address-bar URL, jk fetches the bytes.
 *
 * <ul>
 *   <li>GitHub blob: {@code github.com/u/r/blob/ref/path} → {@code
 *       raw.githubusercontent.com/u/r/ref/path}
 *   <li>GitLab blob: {@code /-/blob/} → {@code /-/raw/}
 *   <li>Bitbucket src: {@code bitbucket.org/u/r/src/…} → {@code bitbucket.org/u/r/raw/…}
 *   <li>Gist page: {@code gist.github.com/u/id} → {@code gist.githubusercontent.com/u/id/raw}
 *       (single-file gists; the redirect serves the newest revision)
 * </ul>
 *
 * Anything else passes through unchanged. The trust check (§7) runs against the URL the user
 * typed, before this rewrite.
 */
public final class UrlRewriter {

    private static final Pattern GITHUB_BLOB =
            Pattern.compile("https://github\\.com/([^/]+)/([^/]+)/blob/(.+)");
    private static final Pattern GIST_PAGE =
            Pattern.compile("https://gist\\.github\\.com/([^/]+)/([0-9a-fA-F]+)/?");
    private static final Pattern BITBUCKET_SRC =
            Pattern.compile("https://bitbucket\\.org/([^/]+)/([^/]+)/src/(.+)");

    private UrlRewriter() {}

    public static String rewrite(String url) {
        String u = url.trim();
        Matcher m = GITHUB_BLOB.matcher(u);
        if (m.matches()) {
            return "https://raw.githubusercontent.com/" + m.group(1) + "/" + m.group(2) + "/" + m.group(3);
        }
        if (u.contains("/-/blob/")) {
            return u.replaceFirst("/-/blob/", "/-/raw/");
        }
        m = BITBUCKET_SRC.matcher(u);
        if (m.matches()) {
            return "https://bitbucket.org/" + m.group(1) + "/" + m.group(2) + "/raw/" + m.group(3);
        }
        m = GIST_PAGE.matcher(u);
        if (m.matches()) {
            return "https://gist.githubusercontent.com/" + m.group(1) + "/" + m.group(2) + "/raw";
        }
        return u;
    }
}
