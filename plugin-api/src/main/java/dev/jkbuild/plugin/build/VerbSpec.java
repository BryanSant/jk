// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.build;

import java.util.Objects;

/**
 * A plugin-declared command (build-plugins plan row 11 — rare by design): {@code jk <name>}
 * dispatches to the plugin's worker when the project's jk.toml declares the plugin's table.
 * The body runs in the worker JVM and streams user-facing lines through {@link VerbExec#out};
 * its return value is the command's exit code.
 */
public final class VerbSpec {

    /** The verb body: runs worker-side against the exec facade; returns the exit code. */
    public interface Body {
        int run(VerbExec exec) throws Exception;
    }

    private final String name;
    private String description = "";
    private Body body;

    private VerbSpec(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public static VerbSpec named(String name) {
        return new VerbSpec(name);
    }

    public VerbSpec description(String description) {
        this.description = description == null ? "" : description;
        return this;
    }

    public VerbSpec run(Body body) {
        this.body = body;
        return this;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Body body() {
        return body;
    }
}
