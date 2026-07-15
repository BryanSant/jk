// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.repo.s3;

import build.jumpkick.credential.RepoCredential;
import build.jumpkick.http.Http;
import build.jumpkick.model.ObjectStoreConfig;
import build.jumpkick.repo.RepoTransport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link RepoTransport} for {@code s3://} (and S3-compatible stores like MinIO and the GCS XML
 * API). Maps a repo URL {@code s3://<bucket>/<prefix>} plus an artifact path to a path-style object
 * URL {@code <endpoint>/<bucket>/<prefix>/<path>} and signs each request with {@link SigV4Signer}
 * (service {@code s3}). Path-style addressing is used throughout so the same code works against AWS
 * and a custom endpoint.
 *
 * <p>Configuration comes from the AWS environment (see {@link AwsCredentialChain}): region from
 * {@code AWS_REGION}/profile (default {@code us-east-1}), and an optional {@code AWS_ENDPOINT_URL}
 * override for MinIO/S3-compatible hosts (otherwise {@code https://s3.<region>.amazonaws.com}).
 * With no credentials, requests are sent unsigned — fine for public buckets, 403 for private.
 */
public final class S3Transport implements RepoTransport {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Http http;
    private final URI endpoint; // e.g. https://s3.us-east-1.amazonaws.com  (no trailing slash)
    private final String region;
    private final Optional<AwsCredentials> credentials;
    private final Supplier<Instant> clock;

    public S3Transport(
            Http http, URI endpoint, String region, Optional<AwsCredentials> credentials, Supplier<Instant> clock) {
        this.http = http;
        this.endpoint = stripTrailingSlash(endpoint);
        this.region = region;
        this.credentials = credentials;
        this.clock = clock;
    }

    /** Resolve endpoint/region/credentials from the AWS environment for an {@code s3://} URL. */
    public static S3Transport fromEnv(Http http, URI s3Url, AwsCredentialChain chain, Function<String, String> env) {
        return forS3(http, s3Url, ObjectStoreConfig.EMPTY, chain, env);
    }

    /**
     * Transport for an {@code s3://} URL, merging explicit per-repo {@link ObjectStoreConfig} (from
     * {@code jk.toml}) over the AWS environment / default chain. Region: config → chain (env/profile)
     * → {@code us-east-1}. Endpoint: config → {@code AWS_ENDPOINT_URL} → {@code
     * s3.<region>.amazonaws.com}. Credentials: explicit config keys → chain → none (unsigned/public).
     */
    public static S3Transport forS3(
            Http http, URI s3Url, ObjectStoreConfig cfg, AwsCredentialChain chain, Function<String, String> env) {
        String region = firstNonBlank(
                cfg.region(),
                chain.resolve(cfg.region()).map(AwsCredentials::region).orElse(null),
                "us-east-1");
        Optional<AwsCredentials> creds = resolveCreds(cfg, chain, region);
        String endpointOverride = firstNonBlank(cfg.endpoint(), env.apply("AWS_ENDPOINT_URL"));
        URI endpoint = endpointOverride != null
                ? URI.create(endpointOverride.strip())
                : URI.create("https://s3." + region + ".amazonaws.com");
        return new S3Transport(http, endpoint, region, creds, Instant::now);
    }

    /**
     * Transport for a {@code gs://} URL via Google Cloud Storage's S3-compatible XML API: the same
     * SigV4 machinery pointed at {@code storage.googleapis.com} with GCS HMAC keys (explicit config
     * keys or the AWS env/credentials chain). Region is fixed to {@code auto} as GCS's V4 interop
     * expects; {@code endpoint}/{@code AWS_ENDPOINT_URL} overrides are honoured (proxies/tests).
     *
     * <p>NOTE: confirm the GCS V4 region/service against a real bucket before relying on it — see
     * docs/artifact-repos.md.
     */
    public static S3Transport forGcs(Http http, URI gsUrl, AwsCredentialChain chain, Function<String, String> env) {
        return forGcs(http, gsUrl, ObjectStoreConfig.EMPTY, chain, env);
    }

    /** {@link #forGcs(Http, URI, AwsCredentialChain, Function)} with explicit per-repo config. */
    public static S3Transport forGcs(
            Http http, URI gsUrl, ObjectStoreConfig cfg, AwsCredentialChain chain, Function<String, String> env) {
        Optional<AwsCredentials> creds = resolveCreds(cfg, chain, "auto");
        String endpointOverride = firstNonBlank(cfg.endpoint(), env.apply("AWS_ENDPOINT_URL"));
        URI endpoint = endpointOverride != null
                ? URI.create(endpointOverride.strip())
                : URI.create("https://storage.googleapis.com");
        return new S3Transport(http, endpoint, "auto", creds, Instant::now);
    }

    /**
     * Explicit config credentials win; otherwise the AWS default chain; both stamped with {@code
     * region}.
     */
    private static Optional<AwsCredentials> resolveCreds(
            ObjectStoreConfig cfg, AwsCredentialChain chain, String region) {
        if (cfg.hasExplicitCredentials()) {
            String token = (cfg.sessionToken() != null && !cfg.sessionToken().isBlank()) ? cfg.sessionToken() : null;
            return Optional.of(new AwsCredentials(cfg.accessKey(), cfg.secretKey(), token, region));
        }
        return chain.resolve(region)
                .map(c -> new AwsCredentials(c.accessKeyId(), c.secretAccessKey(), c.sessionToken(), region));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return null;
    }

    @Override
    public Optional<byte[]> fetch(URI uri, RepoCredential ignored) throws IOException, InterruptedException {
        URI object = objectUri(uri);
        String payloadHash = SigV4Signer.sha256Hex(new byte[0]);
        HttpResponse<byte[]> resp = http.get(object, signedHeaders("GET", object, payloadHash));
        int status = resp.statusCode();
        if (status == 404) return Optional.empty();
        if (status >= 400) throw new IOException("S3 HTTP " + status + " fetching " + object);
        return Optional.of(resp.body());
    }

    @Override
    public int put(URI uri, byte[] body, String contentType, RepoCredential ignored)
            throws IOException, InterruptedException {
        URI object = objectUri(uri);
        String payloadHash = SigV4Signer.sha256Hex(body);
        Map<String, String> headers = signedHeaders("PUT", object, payloadHash);
        if (contentType != null && !contentType.isBlank()) headers.put("Content-Type", contentType);
        return http.put(object, body, headers).statusCode();
    }

    /** {@code s3://bucket/prefix...} → {@code <endpoint>/bucket/prefix...} (path-style). */
    private URI objectUri(URI s3Url) {
        String bucket = s3Url.getHost();
        String key = s3Url.getPath() == null ? "" : s3Url.getPath(); // already starts with '/'
        return URI.create(endpoint.toString() + "/" + bucket + key);
    }

    /** Build the SigV4-signed request headers (or just host, when anonymous). */
    private Map<String, String> signedHeaders(String method, URI object, String payloadHash) {
        String amzDate = AMZ_DATE.format(clock.get());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        if (credentials.isEmpty()) {
            return headers; // unsigned — public bucket
        }
        AwsCredentials c = credentials.get();
        if (c.sessionToken() != null) headers.put("x-amz-security-token", c.sessionToken());

        // Headers that go into the signature: host + the x-amz-* set above.
        Map<String, String> toSign = new LinkedHashMap<>(headers);
        toSign.put("host", hostHeader(object));

        String authorization = SigV4Signer.authorization(
                method, object, toSign, payloadHash, c.accessKeyId(), c.secretAccessKey(), region, "s3", amzDate);
        headers.put("Authorization", authorization);
        return headers;
    }

    /** Host header including a non-default port, as the signature requires. */
    private static String hostHeader(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean defaultPort = port == -1 || (https && port == 443) || (!https && port == 80);
        return defaultPort ? host : host + ":" + port;
    }

    private static URI stripTrailingSlash(URI uri) {
        String s = uri.toString();
        return s.endsWith("/") ? URI.create(s.substring(0, s.length() - 1)) : uri;
    }
}
