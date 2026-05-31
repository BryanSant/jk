// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.http.Http;

import java.net.URI;
import java.util.Locale;

/**
 * Selects a {@link RepoTransport} for a repository URL by scheme — the single
 * place new transports are registered. Today only {@code http}/{@code https}
 * are handled (via {@link HttpTransport}); Phase 3 adds {@code s3://},
 * {@code gs://}, {@code azblob://}, and {@code file://} here (see
 * docs/artifact-repos.md). Keeping the dispatch in one spot is what lets the
 * object-store work drop in without touching {@link MavenRepo} or the
 * publisher.
 */
public final class RepoTransports {

    private RepoTransports() {}

    /**
     * The transport for {@code url}'s scheme. {@code http} expects the shared
     * {@link Http} client; an unsupported scheme is a clear error rather than a
     * silent fallthrough, so a mistyped or not-yet-supported URL fails loudly.
     */
    public static RepoTransport forUrl(URI url, Http http) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "http", "https" -> new HttpTransport(http);
            default -> throw new IllegalArgumentException(
                    "no repository transport for scheme '" + scheme + "' in " + url
                            + " (s3/gs/azblob/file arrive in Phase 3)");
        };
    }
}
