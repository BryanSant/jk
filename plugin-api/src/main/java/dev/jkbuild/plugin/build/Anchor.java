// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.build;

/**
 * Pipeline anchor points a {@link StepSpec} orders itself against (build-plugins plan §3.2).
 * Anchors, not phase-name coupling: the engine maps each anchor onto its internal {@code
 * Phase.requires} graph, so plugins never learn jk's phase names and the pipeline can evolve
 * without breaking plugins.
 *
 * <p>{@code COMPILE} means "the compiled classes <em>and copied resources</em> are in the classes
 * dir" — a step anchored after COMPILE may read a complete classes tree (Spring's AOT context
 * refresh reads {@code application.properties} from it, Android's aapt2 reads merged resources).
 */
public enum Anchor {
    RESOLVE,
    COMPILE,
    TEST,
    PACKAGE,
    RUN_PREPARE;

    /** The manifest/wire spelling ({@code compile}, {@code run-prepare}, …). */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public static Anchor fromWire(String name) {
        return valueOf(name.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
    }
}
