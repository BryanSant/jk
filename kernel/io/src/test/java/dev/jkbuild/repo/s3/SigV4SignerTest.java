// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SigV4SignerTest {

    // The canonical aws-sig-v4-test-suite credentials/clock.
    private static final String AK = "AKIDEXAMPLE";
    private static final String SK = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String DATE = "20150830T123600Z";

    @Test
    void get_vanilla_matches_aws_test_suite_vector() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "example.amazonaws.com");
        headers.put("X-Amz-Date", DATE);

        String emptyPayloadHash = SigV4Signer.sha256Hex(new byte[0]);
        String auth = SigV4Signer.authorization(
                "GET",
                URI.create("https://example.amazonaws.com/"),
                headers,
                emptyPayloadHash,
                AK,
                SK,
                "us-east-1",
                "service",
                DATE);

        // Authoritative expected value from the get-vanilla test case.
        assertThat(auth)
                .isEqualTo("AWS4-HMAC-SHA256 "
                        + "Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                        + "SignedHeaders=host;x-amz-date, "
                        + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
    }

    @Test
    void sha256_of_empty_is_the_known_constant() {
        assertThat(SigV4Signer.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void canonical_uri_encodes_segments_but_keeps_slashes() {
        // %20 in the literal → getPath() decodes to a space → canonicalUri re-encodes it.
        assertThat(SigV4Signer.canonicalUri(URI.create("https://h/my-bucket/a%20b/c+d.jar")))
                .isEqualTo("/my-bucket/a%20b/c%2Bd.jar");
        assertThat(SigV4Signer.canonicalUri(URI.create("https://h"))).isEqualTo("/");
    }

    @Test
    void canonical_query_is_sorted_and_encoded() {
        assertThat(SigV4Signer.canonicalQuery(URI.create("https://h/?b=2&a=1&c=x%20y")))
                .isEqualTo("a=1&b=2&c=x%20y");
        assertThat(SigV4Signer.canonicalQuery(URI.create("https://h/"))).isEqualTo("");
    }

    @Test
    void rfc3986_leaves_unreserved_and_uppercases_percent_encoding() {
        assertThat(SigV4Signer.rfc3986("aZ09-_.~")).isEqualTo("aZ09-_.~");
        assertThat(SigV4Signer.rfc3986("a/b c")).isEqualTo("a%2Fb%20c");
    }
}
