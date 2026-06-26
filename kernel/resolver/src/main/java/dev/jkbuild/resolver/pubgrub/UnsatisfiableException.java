// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

/**
 * Raised when PubGrub determines that no version assignment satisfies the supplied constraints.
 * Carries the root-level incompatibility that fired so the diagnostics renderer can walk back
 * through the cause DAG and produce a human-readable explanation.
 */
public final class UnsatisfiableException extends RuntimeException {

    private final Incompatibility rootCause;

    public UnsatisfiableException(Incompatibility rootCause) {
        super("no satisfying assignment found");
        this.rootCause = rootCause;
    }

    public UnsatisfiableException(String message, Incompatibility rootCause) {
        super(message);
        this.rootCause = rootCause;
    }

    public Incompatibility rootCause() {
        return rootCause;
    }
}
