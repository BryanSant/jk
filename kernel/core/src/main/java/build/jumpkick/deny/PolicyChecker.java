// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.deny;

import build.jumpkick.lock.Lockfile;
import build.jumpkick.lock.RepoSource;
import build.jumpkick.model.DenyPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Apply a {@link DenyPolicy} to the resolved {@link Lockfile} (PRD §23.6). v0.8 first cut checks
 * source-URL host denylisting; license inspection (which needs to read each package's POM) is
 * layered on in a follow-up.
 */
public final class PolicyChecker {

    private final DenyPolicy policy;

    public PolicyChecker(DenyPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public record Violation(String module, String version, String reason) {}

    public List<Violation> check(Lockfile lock) {
        List<Violation> out = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            // Source format: `<name>+<url>`; we test the host portion. RepoSource.url() applies the
            // lenient split (url after the first '+', else the whole string) this check has always used.
            String url = RepoSource.parse(pkg.source()).url();
            String host = hostOf(url);
            for (String denied : policy.deniedSources()) {
                if (hostMatches(host, denied)) {
                    out.add(new Violation(
                            pkg.name(),
                            pkg.version(),
                            "source `" + host + "` matches denylisted host `" + denied + "`"));
                    break;
                }
            }
        }
        return out;
    }

    private static String hostOf(String url) {
        int scheme = url.indexOf("://");
        String rest = scheme >= 0 ? url.substring(scheme + 3) : url;
        int slash = rest.indexOf('/');
        String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
        int colon = hostPort.indexOf(':');
        return (colon >= 0 ? hostPort.substring(0, colon) : hostPort).toLowerCase(Locale.ROOT);
    }

    private static boolean hostMatches(String host, String pattern) {
        String lower = pattern.toLowerCase(Locale.ROOT);
        if (host.equals(lower)) return true;
        // Allow trailing-suffix match e.g. ".bintray.com".
        return host.endsWith("." + lower) || host.endsWith(lower);
    }
}
