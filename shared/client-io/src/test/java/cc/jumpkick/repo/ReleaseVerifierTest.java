// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseVerifierTest {

    @Test
    void signature_verifies_against_a_trusted_key_and_tampering_is_fatal() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] sums = "abc123  jk-engine-1.0.0.jar\n".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(sums);
        String sig = Base64.getEncoder().encodeToString(signer.sign());
        String pub = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        ReleaseVerifier verifier = ReleaseVerifier.of(List.of(pub));
        assertThat(verifier.available()).isTrue();
        verifier.verify(sums, sig); // does not throw

        // Rotation shape: an unknown key first, the trusted one second — any trusted key passes.
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String otherPub = Base64.getEncoder().encodeToString(other.getPublic().getEncoded());
        ReleaseVerifier.of(List.of(otherPub, pub)).verify(sums, sig);

        // Tampered sums: fatal, with the refusal spelled out.
        byte[] tampered = "evil000  jk-engine-1.0.0.jar\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier.verify(tampered, sig))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSING");

        // Wrong key only: fatal.
        assertThatThrownBy(() -> ReleaseVerifier.of(List.of(otherPub)).verify(sums, sig))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSING");

        // No keys at all: verification unavailable is itself fatal.
        assertThatThrownBy(() -> ReleaseVerifier.of(List.of()).verify(sums, sig))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no trusted release keys");
    }
}
