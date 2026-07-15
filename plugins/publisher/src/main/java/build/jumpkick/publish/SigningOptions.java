// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.publish;

/**
 * Bundle of optional signing strategies applied per artifact during a {@link
 * MavenPublisher#publish} call. Either field can be {@code null}; if both are, no signatures are
 * produced (the {@link #none()} factory).
 */
public record SigningOptions(GpgSigner gpg, SigstoreSigner sigstore) {

    public static SigningOptions none() {
        return new SigningOptions(null, null);
    }

    public static SigningOptions of(GpgSigner gpg) {
        return new SigningOptions(gpg, null);
    }

    public static SigningOptions of(GpgSigner gpg, SigstoreSigner sigstore) {
        return new SigningOptions(gpg, sigstore);
    }

    public boolean isNoop() {
        return gpg == null && sigstore == null;
    }
}
