// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

/**
 * The single owner of the lockfile artifact {@code source}-string split. A source has the form
 * {@code "<name>+<url>"} (e.g. {@code "central+https://repo1.maven.org/maven2/"}) — or a synthetic
 * git source ({@code "git:<coord>:<version>"}) / bare marker ({@code "local"}) that has no
 * {@code '+'}. The split is always on the <em>first</em> {@code '+'}.
 *
 * <p>Historically two call sites hand-split this string with subtly different boundary rules; this
 * type exposes <em>both</em> halves so each site keeps its exact original contract:
 *
 * <ul>
 *   <li>{@link #name()} — the <em>strict</em> repo name (before the {@code '+'}), or {@code null}
 *       when there is no <em>valid</em> {@code '+'}. Valid means {@code plus > 0 && plus < len-1}:
 *       the {@code '+'} must be neither the first nor the last character. This mirrors
 *       {@code RepoArtifactResolver.repoName} exactly (its {@code plus <= 0 || plus >= len-1 ->
 *       null} rule). Used by callers that need a real named repo.
 *   <li>{@link #url()} — the <em>lenient</em> url (after the {@code '+'}) when {@code plus > 0},
 *       otherwise the <em>whole</em> source string. This mirrors {@code PolicyChecker}'s
 *       {@code plus > 0 ? substring(plus+1) : source} rule exactly, whole-string fallback included.
 *       Used by host/URL inspection that must degrade gracefully on non-{@code <name>+<url>} inputs.
 * </ul>
 *
 * <p><b>The two contracts intentionally disagree at the trailing-{@code '+'} boundary.</b> For a
 * source like {@code "central+"} ({@code plus == len-1}): {@link #name()} returns {@code null}
 * (invalid — the {@code '+'} is the last char) while {@link #url()} returns {@code ""} (its rule is
 * only {@code plus > 0}, which holds, so it splits and yields the empty tail). Both behaviours are
 * the originals, preserved verbatim; this type simply applies each accessor's own boundary rule to
 * the same underlying string rather than forcing one rule on both.
 */
public record RepoSource(String source) {

    /** Parse a lockfile source string. Splits (lazily, per accessor) on the first {@code '+'}. */
    public static RepoSource parse(String source) {
        return new RepoSource(source);
    }

    /**
     * The strict {@code <name>} before the first {@code '+'}, or {@code null} when the source is
     * {@code null} or has no valid {@code '+'} (the {@code '+'} is absent, first, or last). Mirrors
     * {@code RepoArtifactResolver.repoName}'s {@code plus <= 0 || plus >= len-1 -> null} contract.
     */
    public String name() {
        if (source == null) return null;
        int plus = source.indexOf('+');
        if (plus <= 0 || plus >= source.length() - 1) return null;
        return source.substring(0, plus);
    }

    /**
     * The lenient url: the tail after the first {@code '+'} when {@code plus > 0}, otherwise the
     * whole source string. Mirrors {@code PolicyChecker}'s {@code plus > 0 ? substring(plus+1) :
     * source} contract, including the whole-string fallback for sources without a leading name.
     * (Returns {@code null} for a {@code null} source, which no caller passes.)
     */
    public String url() {
        if (source == null) return null;
        int plus = source.indexOf('+');
        return plus > 0 ? source.substring(plus + 1) : source;
    }
}
