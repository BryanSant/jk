// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo.s3;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 request signing (the {@code Authorization: AWS4-HMAC-SHA256}
 * scheme), hand-rolled over the JDK's crypto so jk needs no AWS SDK. Used by
 * {@link S3Transport} for S3 and S3-compatible object stores (MinIO, GCS XML
 * API). See docs/artifact-repos.md.
 *
 * <p>Verified against the canonical {@code aws-sig-v4-test-suite}
 * ({@code get-vanilla}) — see SigV4SignerTest.
 */
public final class SigV4Signer {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";

    private SigV4Signer() {}

    /**
     * Compute the {@code Authorization} header value for a request.
     *
     * @param method      HTTP method (GET, PUT, …)
     * @param uri         full request URI (host taken from it; path/query signed)
     * @param signedHeaders headers to include in the signature — MUST contain
     *                    {@code host}; callers typically also pass
     *                    {@code x-amz-date} and {@code x-amz-content-sha256}.
     *                    Header names are treated case-insensitively.
     * @param payloadHashHex hex SHA-256 of the body (use {@link #sha256Hex} or
     *                    {@code "UNSIGNED-PAYLOAD"})
     * @param accessKeyId AWS access key id
     * @param secretKey   AWS secret access key
     * @param region      e.g. {@code us-east-1}
     * @param service     e.g. {@code s3}
     * @param amzDate     ISO basic timestamp {@code yyyyMMdd'T'HHmmss'Z'}
     */
    public static String authorization(String method, URI uri,
                                       Map<String, String> signedHeaders, String payloadHashHex,
                                       String accessKeyId, String secretKey,
                                       String region, String service, String amzDate) {
        // Normalise headers: lowercase names, trimmed values, sorted.
        TreeMap<String, String> headers = new TreeMap<>();
        for (Map.Entry<String, String> e : signedHeaders.entrySet()) {
            headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().trim());
        }

        String canonicalHeaders = headers.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue() + "\n")
                .reduce("", String::concat);
        String signedHeaderNames = String.join(";", headers.keySet());

        String canonicalRequest = method + "\n"
                + canonicalUri(uri) + "\n"
                + canonicalQuery(uri) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaderNames + "\n"
                + payloadHashHex;

        String dateStamp = amzDate.substring(0, 8);
        String scope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] signingKey = signingKey(secretKey, dateStamp, region, service);
        String signature = hex(hmac(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

        return ALGORITHM
                + " Credential=" + accessKeyId + "/" + scope
                + ", SignedHeaders=" + signedHeaderNames
                + ", Signature=" + signature;
    }

    /** Canonical URI: the path with each segment RFC3986-encoded; {@code /} preserved; empty → "/". */
    static String canonicalUri(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.isEmpty()) return "/";
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) out.append('/');
            out.append(rfc3986(segments[i]));
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    /** Canonical query string: params sorted by encoded key, {@code key=value} joined by {@code &}. */
    static String canonicalQuery(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return "";
        TreeMap<String, String> params = new TreeMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            // Re-encode from the decoded form to get canonical RFC3986 encoding.
            params.put(rfc3986(urlDecode(k)), rfc3986(urlDecode(v)));
        }
        StringJoiner sj = new StringJoiner("&");
        params.forEach((k, v) -> sj.add(k + "=" + v));
        return sj.toString();
    }

    public static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] signingKey(String secretKey, String dateStamp, String region, String service) {
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8),
                dateStamp.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmac(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmac(kRegion, service.getBytes(StandardCharsets.UTF_8));
        return hmac(kService, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                .append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }

    /** RFC 3986 unreserved set is left as-is; everything else percent-encoded (uppercase hex). */
    static String rfc3986(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xf, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
