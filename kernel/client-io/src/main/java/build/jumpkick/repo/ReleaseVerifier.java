// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.repo;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Release authenticity (engine-versioning-plan §4): the pipeline signs each release's
 * {@code SHA256SUMS} with an Ed25519 release key; every verifier checks
 * signature-then-hash before any byte is materialized or executed. {@code SHA256SUMS.sig}
 * is the base64 of a raw Ed25519 signature over the exact {@code SHA256SUMS} bytes —
 * verifiable with nothing but the JDK (or {@code openssl pkeyutl}), no ecosystem dependency.
 *
 * <p>Trust: the current release key ships baked into the client; each release may announce a
 * successor ({@code NEXT_RELEASE_KEY} line in SHA256SUMS, carried into the next client) so
 * rotation follows the forward-compat pattern the signing schema established. Enterprise /
 * air-gapped hosts override via {@code [release] trusted-keys} in config.toml. A signature by
 * ANY trusted key passes.
 *
 * <p>Integrity vs authenticity: once a version is pinned in {@code jk.lock} with its sha256,
 * later fetches verify against the PIN — the signature only gates first acquisition, so a
 * compromised release site can't swap bytes under an existing pin.
 */
public final class ReleaseVerifier {

    /**
     * The baked-in release public key (base64 X.509/SPKI Ed25519). Empty until the first signed
     * release ships — an empty key set means "verification unavailable", which callers treat as
     * fatal for release-version fetches and as expected for -SNAPSHOT dev flows.
     */
    public static final String BUILT_IN_KEY = "";

    private final List<PublicKey> trusted;

    private ReleaseVerifier(List<PublicKey> trusted) {
        this.trusted = trusted;
    }

    /** The verifier for this host: baked-in key plus {@code [release] trusted-keys} overrides. */
    public static ReleaseVerifier current(List<String> configuredKeys) {
        List<PublicKey> keys = new ArrayList<>();
        if (!BUILT_IN_KEY.isEmpty()) parse(BUILT_IN_KEY).ifPresentOrElse(keys::add, () -> {});
        for (String k : configuredKeys) parse(k).ifPresent(keys::add);
        return new ReleaseVerifier(keys);
    }

    /** A verifier trusting exactly {@code keys} — tests, and pinned enterprise setups. */
    public static ReleaseVerifier of(List<String> keys) {
        List<PublicKey> parsed = new ArrayList<>();
        for (String k : keys) parse(k).ifPresent(parsed::add);
        return new ReleaseVerifier(parsed);
    }

    /** True when at least one trusted key is configured — verification is possible at all. */
    public boolean available() {
        return !trusted.isEmpty();
    }

    /**
     * Verify {@code signatureBase64} over {@code sumsBytes} against any trusted key. Throws with
     * an actionable message on failure — a bad signature must never degrade to a warning.
     */
    public void verify(byte[] sumsBytes, String signatureBase64) throws IOException {
        if (trusted.isEmpty()) {
            throw new IOException("no trusted release keys are configured — cannot verify this release"
                    + " (set [release] trusted-keys in config.toml, or upgrade to a client with a baked-in key)");
        }
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(signatureBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("release signature is not valid base64 — the download may be corrupt");
        }
        for (PublicKey key : trusted) {
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key);
                verifier.update(sumsBytes);
                if (verifier.verify(sig)) return;
            } catch (GeneralSecurityException ignored) {
                // try the next trusted key
            }
        }
        throw new IOException("release signature does not verify against any trusted key —"
                + " REFUSING this release (a mirror, proxy, or the release site may be compromised)");
    }

    private static java.util.Optional<PublicKey> parse(String base64Spki) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Spki.trim());
            return java.util.Optional.of(
                    KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
