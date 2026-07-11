// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.build;

import java.util.Objects;

/**
 * A declared step/packager input (build-plugins plan §3.2). Declaring inputs is the whole caching
 * contract: the engine fingerprints exactly these to key the action cache, so the author never
 * sees action keys, freshness stamps, or CAS paths — and gets correct incrementality for free.
 *
 * @param kind what the input is
 * @param step the producing step's name — {@link Kind#STEP_OUTPUT} only, null otherwise
 */
public record In(Kind kind, String step) {

    public enum Kind {
        /** The module's compiled classes dir (resources already copied in). */
        CLASSES,
        /** The resolved production RUNTIME classpath — jar paths, lock-ordered. */
        RUNTIME_CLASSPATH,
        /** As {@link #RUNTIME_CLASSPATH}, plus real artifact names + snapshot flags per entry. */
        RUNTIME_ENTRIES,
        /** The plugin's own validated config table — any config change re-runs. */
        CONFIG,
        /** Another step's declared outputs (chaining, e.g. packaging over an AOT step). */
        STEP_OUTPUT
    }

    public In {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.STEP_OUTPUT) == (step == null)) {
            throw new IllegalArgumentException("step name is required for STEP_OUTPUT and only for STEP_OUTPUT");
        }
    }

    public static In classes() {
        return new In(Kind.CLASSES, null);
    }

    public static In runtimeClasspath() {
        return new In(Kind.RUNTIME_CLASSPATH, null);
    }

    public static In runtimeEntries() {
        return new In(Kind.RUNTIME_ENTRIES, null);
    }

    public static In config() {
        return new In(Kind.CONFIG, null);
    }

    public static In stepOutput(String step) {
        return new In(Kind.STEP_OUTPUT, step);
    }

    /** The wire spelling: {@code classes}, {@code runtime-classpath}, …, {@code step:<name>}. */
    public String wireName() {
        return kind == Kind.STEP_OUTPUT
                ? "step:" + step
                : kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public static In fromWire(String name) {
        if (name.startsWith("step:")) return stepOutput(name.substring("step:".length()));
        return new In(Kind.valueOf(name.toUpperCase(java.util.Locale.ROOT).replace('-', '_')), null);
    }
}
