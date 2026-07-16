// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.publish.testkit.GpgTestFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpgSignerTest {

    @Test
    void sign_and_verify_round_trip(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generate(tempDir, "swordfish");
        GpgSigner signer = GpgSigner.fromKeyFile(key.secretKeyFile(), "swordfish".toCharArray());

        byte[] data = "the artifact bytes".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.signArmored(data);

        String text = new String(signature, StandardCharsets.US_ASCII);
        assertThat(text).startsWith("-----BEGIN PGP SIGNATURE-----");
        assertThat(text).endsWith("-----END PGP SIGNATURE-----\n");

        // Signature verifies against the matching public key.
        GpgTestFixture.verifyDetached(data, signature, key.publicRing());

        // And does NOT verify against tampered bytes.
        byte[] tampered = "tampered".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> GpgTestFixture.verifyDetached(tampered, signature, key.publicRing()))
                .hasMessageContaining("did not verify");
    }

    @Test
    void wrong_passphrase_is_rejected_with_io_exception(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generate(tempDir, "correct");
        assertThatThrownBy(() -> GpgSigner.fromKeyFile(key.secretKeyFile(), "wrong".toCharArray()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("GPG key");
    }

    @Test
    void empty_keyring_is_rejected(@TempDir Path tempDir) throws Exception {
        Path empty = tempDir.resolve("empty.asc");
        Files.writeString(empty, "");
        assertThatThrownBy(() -> GpgSigner.fromKeyFile(empty, new char[0])).isInstanceOf(IOException.class);
    }
}
