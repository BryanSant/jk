// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Syntactic classification of a {@code jk tool run|install} target (docs/tool-targets-plan.md §2).
 *
 * <p>Purely syntactic — no catalog lookups, no network. The one filesystem touch is the
 * first-match-wins "does it exist locally" rule, so an existing {@code ./gh:weird-dir} is a path,
 * not a git shorthand. Sniffing order is the spec:
 *
 * <ol>
 *   <li>A runnable extension ({@code .java/.kt/.kts/.jar}) → {@link RunnableFile}, even when the
 *       file is missing — the handler renders the proper not-found error.
 *   <li>An existing directory → {@link Directory}; an existing file of any other type →
 *       {@link UnsupportedFile}.
 *   <li>Git syntax → {@link Git}: {@code git+http(s)://}, {@code git://}, {@code ssh://},
 *       {@code git@host:…}, forge shorthands ({@code gh: gl: bb: sr:}), an {@code http(s)} URL
 *       ending {@code .git}, or a bare forge repo root ({@code https://github.com/user/repo}).
 *   <li>Any other {@code http(s)://} (or {@code file://}) → {@link Url}.
 *   <li>Contains {@code :} → {@link Gav} (the {@link cc.jumpkick.model.ToolCoordSpec} grammar;
 *       parsing/validation is the consumer's job so classification never throws).
 *   <li>{@code name@suffix} — §6.1's syntax discriminators: a suffix containing {@code /} or an
 *       <em>infix</em> {@code ~} is a {@link JBangAlias} catalog ref; anything else (including a
 *       leading selector operator) stays a {@link CatalogName} whose suffix the consumer
 *       interprets — the jk-catalog lookup IS the disambiguation semantics.
 *   <li>Bare word → {@link CatalogName} with no suffix.
 * </ol>
 */
public sealed interface ToolTarget {

    /** The original target text. */
    String raw();

    /** A {@code .java/.kt/.kts/.jar} target (existing or not — extension is the signal). */
    record RunnableFile(Path path, String raw) implements ToolTarget {}

    /** An existing directory (jk project / JBang folder — step 2). */
    record Directory(Path path, String raw) implements ToolTarget {}

    /** An existing file that no runner handles. */
    record UnsupportedFile(Path path, String raw) implements ToolTarget {}

    /** A git source (step 4). */
    record Git(String raw) implements ToolTarget {}

    /** A web URL (step 3). */
    record Url(String raw) implements ToolTarget {}

    /** A Maven coordinate spec — {@code g:a[:v|@selector]}. */
    record Gav(String raw) implements ToolTarget {}

    /** A JBang {@code alias@catalog} reference (step 4). */
    record JBangAlias(String raw) implements ToolTarget {}

    /**
     * A bare library-catalog name, optionally {@code @suffix}. {@code suffix} is null when absent;
     * the consumer resolves the name against the layered catalog and parses the suffix as a
     * floating version selector (docs/tool-targets-plan.md §6.1).
     */
    record CatalogName(String name, String suffix, String raw) implements ToolTarget {}

    static ToolTarget classify(String raw) {
        Objects.requireNonNull(raw, "raw");
        String s = raw.trim();

        // 1. Runnable extension — same signal ScriptRunner keys on, missing file included.
        // Remote-looking targets skip this rule: https://…/tool.jar is a URL, not a local path.
        String lower = s.toLowerCase(Locale.ROOT);
        boolean remote = s.contains("://")
                || s.startsWith("git@")
                || s.startsWith("gh:")
                || s.startsWith("gl:")
                || s.startsWith("bb:")
                || s.startsWith("sr:");
        if (!remote
                && (lower.endsWith(".java")
                        || lower.endsWith(".kt")
                        || lower.endsWith(".kts")
                        || lower.endsWith(".jar"))) {
            return new RunnableFile(Path.of(s), raw);
        }

        // 2. Existing local paths beat every remote/coordinate interpretation.
        Path asPath = safePath(s);
        if (asPath != null && Files.exists(asPath)) {
            if (Files.isDirectory(asPath)) return new Directory(asPath, raw);
            return new UnsupportedFile(asPath, raw);
        }

        // 3. Git syntax. Any git+<transport> forces git classification (npm-style).
        if (s.startsWith("git+")) return new Git(raw);
        if (s.startsWith("git://") || s.startsWith("ssh://") || s.startsWith("git@")) return new Git(raw);
        if (s.startsWith("gh:") || s.startsWith("gl:") || s.startsWith("bb:") || s.startsWith("sr:")) {
            return new Git(raw);
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return isForgeRepoUrl(s) ? new Git(raw) : new Url(raw);
        }
        if (s.startsWith("file://")) return new Url(raw);

        // 4. Coordinate spec — the colon must be in the body (before any `@`): g:a[:v|@sel].
        // A colon only after the `@` is a catalog ref's host:port, not a coordinate.
        int at = s.indexOf('@');
        String body = at >= 0 ? s.substring(0, at) : s;
        if (body.contains(":")) return new Gav(raw);

        // 5/6. name[@suffix] — §6.1 syntax discriminators, then the bare catalog name.
        if (at > 0 && at < s.length() - 1) {
            String name = s.substring(0, at);
            String suffix = s.substring(at + 1);
            if (suffix.contains("/") || suffix.indexOf('~') > 0) return new JBangAlias(raw);
            return new CatalogName(name, suffix, raw);
        }
        return new CatalogName(s, null, raw);
    }

    /**
     * True when the URL is a bare forge repository root ({@code https://host/user/repo[.git][/]})
     * on a known forge — a repo to clone, not a file to download.
     */
    private static boolean isForgeRepoUrl(String url) {
        String rest = url.substring(url.indexOf("://") + 3);
        int slash = rest.indexOf('/');
        if (slash < 0) return false;
        String host = rest.substring(0, slash);
        if (!host.equals("github.com")
                && !host.equals("gitlab.com")
                && !host.equals("bitbucket.org")
                && !host.equals("codeberg.org")
                && !host.equals("sr.ht")) {
            String path0 = rest.substring(slash + 1);
            return path0.endsWith(".git"); // unknown host: only the explicit .git suffix is git
        }
        String path = rest.substring(slash + 1);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.endsWith(".git")) return true;
        // Exactly user/repo (allowing an embedded @ref / #rev / !subdir tail on the repo segment).
        int cut = indexOfAny(path, '@', '#', '!');
        if (cut >= 0) path = path.substring(0, cut);
        return path.chars().filter(c -> c == '/').count() == 1 && !path.isEmpty();
    }

    private static int indexOfAny(String s, char... chars) {
        for (int i = 0; i < s.length(); i++) {
            for (char c : chars) {
                if (s.charAt(i) == c) return i;
            }
        }
        return -1;
    }

    private static Path safePath(String s) {
        try {
            return Path.of(s);
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }
}
