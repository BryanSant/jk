// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.publish;

import java.io.IOException;

/**
 * Produces a Sigstore Bundle (JSON) over an artifact's bytes — the {@code .sigstore} file every
 * keyless-signed Maven upload ships alongside the artifact (PRD §21.2, §23.3).
 *
 * <p>The interface deliberately does not expose any {@code dev.sigstore.*} types so callers outside
 * {@code :supply-chain} can use it without pulling sigstore-java onto their compile classpath. The
 * production implementation is {@link KeylessSigstoreSigner}.
 */
@FunctionalInterface
public interface SigstoreSigner {

    /** Sign the given bytes; return the Sigstore Bundle as JSON bytes. */
    byte[] signBundle(byte[] artifact) throws IOException;
}
