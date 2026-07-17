// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;

/**
 * Detached ASCII-armored GPG signatures over artifact bytes, used by {@link MavenPublisher} to emit
 * the {@code .asc} files every Maven Central upload requires (PRD §21.2, §23.2).
 *
 * <p>Implementation is pure-Java via BouncyCastle (impl-plan §4) — no dependency on a system {@code
 * gpg} binary. The signer is built from a secret-key file (ASCII-armored or binary) and a
 * passphrase; the first signing-capable key in the file wins.
 */
public final class GpgSigner {

    private final PGPPrivateKey privateKey;
    private final PGPPublicKey publicKey;

    private GpgSigner(PGPPrivateKey privateKey, PGPPublicKey publicKey) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    }

    /** Load a signer from a secret-key file (armored or binary). */
    public static GpgSigner fromKeyFile(Path keyFile, char[] passphrase) throws IOException {
        Objects.requireNonNull(keyFile, "keyFile");
        try (InputStream raw = Files.newInputStream(keyFile);
                InputStream decoded = PGPUtil.getDecoderStream(raw)) {
            PGPSecretKeyRingCollection rings =
                    new PGPSecretKeyRingCollection(decoded, new JcaKeyFingerprintCalculator());
            for (Iterator<PGPSecretKeyRing> rIter = rings.getKeyRings(); rIter.hasNext(); ) {
                PGPSecretKeyRing ring = rIter.next();
                for (Iterator<PGPSecretKey> kIter = ring.getSecretKeys(); kIter.hasNext(); ) {
                    PGPSecretKey key = kIter.next();
                    if (!key.isSigningKey()) continue;
                    PGPPrivateKey priv = key.extractPrivateKey(
                            new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider())
                                    .build(passphrase == null ? new char[0] : passphrase));
                    return new GpgSigner(priv, key.getPublicKey());
                }
            }
        } catch (PGPException e) {
            throw new IOException("failed to load GPG key from " + keyFile + ": " + e.getMessage(), e);
        }
        throw new IOException("no signing-capable key in " + keyFile);
    }

    /**
     * For programmatic use (e.g. tests): wrap an already-extracted {@link PGPPrivateKey} together
     * with its matching {@link PGPPublicKey}.
     */
    public static GpgSigner of(PGPPrivateKey privateKey, PGPPublicKey publicKey) {
        return new GpgSigner(privateKey, publicKey);
    }

    /**
     * Produce a detached, ASCII-armored signature over {@code data}. The returned bytes are what gets
     * uploaded as the {@code .asc} file.
     */
    public byte[] signArmored(byte[] data) throws IOException {
        try {
            PGPSignatureGenerator gen = new PGPSignatureGenerator(
                    new BcPGPContentSignerBuilder(publicKey.getAlgorithm(), HashAlgorithmTags.SHA256), publicKey);
            gen.init(PGPSignature.BINARY_DOCUMENT, privateKey);
            gen.update(data);
            PGPSignature sig = gen.generate();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ArmoredOutputStream aos = new ArmoredOutputStream(baos)) {
                sig.encode(aos);
            }
            return baos.toByteArray();
        } catch (PGPException e) {
            throw new IOException("GPG signing failed: " + e.getMessage(), e);
        }
    }
}
