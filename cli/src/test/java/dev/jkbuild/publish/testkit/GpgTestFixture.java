// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.publish.testkit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Date;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyPacket;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;

/**
 * Generates an ephemeral PGP keypair for tests: a small (1024-bit) RSA key bound to a test
 * identity, written as an armored secret-key file that {@code GpgSigner.fromKeyFile(Path, char[])}
 * can consume.
 */
public final class GpgTestFixture {

    private GpgTestFixture() {}

    public record GeneratedKey(Path secretKeyFile, char[] passphrase, PGPPublicKeyRing publicRing) {}

    public static GeneratedKey generate(Path dir, String passphrase) throws Exception {
        RSAKeyPairGenerator rsa = new RSAKeyPairGenerator();
        // 1024-bit is fast for tests; never use this size in production.
        rsa.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 1024, 12));
        AsymmetricCipherKeyPair pair = rsa.generateKeyPair();
        PGPKeyPair pgpPair =
                new BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, pair, new Date());

        var sha1Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
        PGPKeyRingGenerator gen = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                pgpPair,
                "jk-test <test@example.com>",
                sha1Calc,
                null,
                null,
                new BcPGPContentSignerBuilder(pgpPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                new BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1Calc)
                        .build(passphrase.toCharArray()));

        PGPSecretKeyRing secretRing = gen.generateSecretKeyRing();
        PGPPublicKeyRing publicRing = gen.generatePublicKeyRing();

        Path keyFile = dir.resolve("test-secret.asc");
        try (var out = Files.newOutputStream(keyFile);
                var armored = new ArmoredOutputStream(out)) {
            secretRing.encode(armored);
        }
        return new GeneratedKey(keyFile, passphrase.toCharArray(), publicRing);
    }

    public static void verifyDetached(byte[] data, byte[] armoredSignature, PGPPublicKeyRing publicRing)
            throws IOException, PGPException {
        var decoded = PGPUtil.getDecoderStream(new ByteArrayInputStream(armoredSignature));
        var fact = new PGPObjectFactory(decoded, new JcaKeyFingerprintCalculator());
        Object obj = fact.nextObject();
        if (!(obj instanceof PGPSignatureList list)) {
            throw new IllegalStateException("expected PGPSignatureList, got " + obj);
        }
        PGPSignature sig = list.get(0);
        sig.init(new BcPGPContentVerifierBuilderProvider(), publicRing.getPublicKey(sig.getKeyID()));
        sig.update(data);
        if (!sig.verify()) {
            throw new IllegalStateException("signature did not verify");
        }
    }
}
