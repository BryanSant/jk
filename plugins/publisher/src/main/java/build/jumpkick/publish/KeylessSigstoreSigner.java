// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.publish;

import dev.sigstore.KeylessSigner;
import dev.sigstore.KeylessSignerException;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.trustroot.SigstoreConfigurationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Production {@link SigstoreSigner}: keyless OIDC signing against the public Sigstore instance
 * (Fulcio + Rekor). On CI runners with an OIDC token issuer in scope (GitHub Actions, GitLab,
 * etc.), sigstore-java auto-detects credentials; locally, it falls back to a browser-based
 * device-code flow.
 *
 * <p>Wraps {@link KeylessSigner} so that {@code dev.sigstore.*} types never leak across the {@code
 * :supply-chain} module boundary — {@link KeylessSignerException} surfaces as {@link IOException},
 * which is what every {@code :cli} call site already handles.
 */
public final class KeylessSigstoreSigner implements SigstoreSigner, AutoCloseable {

    private final KeylessSigner signer;

    private KeylessSigstoreSigner(KeylessSigner signer) {
        this.signer = signer;
    }

    /** Initialise a signer using the public Sigstore defaults. */
    public static KeylessSigstoreSigner sigstorePublic() throws IOException {
        try {
            KeylessSigner signer =
                    KeylessSigner.builder().sigstorePublicDefaults().build();
            return new KeylessSigstoreSigner(signer);
        } catch (GeneralSecurityException | SigstoreConfigurationException | RuntimeException e) {
            throw new IOException("failed to initialise Sigstore keyless signer: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] signBundle(byte[] artifact) throws IOException {
        try {
            Bundle bundle = signer.sign(artifact);
            return bundle.toJson().getBytes(StandardCharsets.UTF_8);
        } catch (KeylessSignerException e) {
            throw new IOException("Sigstore signing failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        signer.close();
    }
}
