// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

/**
 * Pipeline anchor points a {@link StepSpec} orders itself against (build-plugins plan §3.2).
 * Anchors, not step-name coupling: the engine maps each anchor onto its internal {@code
 * Step.requires} graph, so plugins never learn jk's step names and the pipeline can evolve
 * without breaking plugins.
 *
 * <p>{@code COMPILE} means "the compiled classes <em>and copied resources</em> are in the classes
 * dir" — a step anchored after COMPILE may read a complete classes tree (Spring's AOT context
 * refresh reads {@code application.properties} from it, Android's aapt2 reads merged resources).
 */
public enum Phase {
    RESOLVE,
    COMPILE,
    TEST,
    PACKAGE,
    RUN,
    IMAGE,
    PUBLISH;

    /** The manifest/wire spelling ({@code compile}, {@code run}, {@code publish}, …). */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public static Phase fromWire(String name) {
        return valueOf(name.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
    }

    /** As {@link #fromWire}, but {@code null}/empty (a step with no declared phase) maps to {@code null}. */
    public static Phase fromWireOrNull(String name) {
        return (name == null || name.isEmpty()) ? null : fromWire(name);
    }
}
