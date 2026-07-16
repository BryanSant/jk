// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * Per-repository configuration for object-store backends ({@code s3://}, {@code gs://}) declared on
 * a {@code [repositories.<name>]} table in {@code jk.toml} — see docs/artifact-repos.md. Every
 * field is optional; an unset field falls back to the AWS environment / default chain, so a
 * zero-config CI setup keeps working. Credentials are typically supplied via {@code ${ENV}}
 * interpolation rather than committed literally.
 *
 * <p>Irrelevant for {@code http(s)} and {@code file://} repositories.
 */
public record ObjectStoreConfig(
        String region, String endpoint, String accessKey, String secretKey, String sessionToken) {

    public static final ObjectStoreConfig EMPTY = new ObjectStoreConfig(null, null, null, null, null);

    /** True when no field is set (the table declared no object-store config). */
    public boolean isEmpty() {
        return region == null && endpoint == null && accessKey == null && secretKey == null && sessionToken == null;
    }

    /** True when both an access key and secret key are present (explicit credentials). */
    public boolean hasExplicitCredentials() {
        return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }
}
