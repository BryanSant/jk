// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.util.Objects;

/**
 * A plugin-declared command (build-plugins plan row 11 — rare by design): {@code jk <name>}
 * dispatches to the plugin's worker when the project's jk.toml declares the plugin's table.
 * The body runs in the worker JVM and streams user-facing lines through {@link PluginCommandExec#out};
 * its return value is the command's exit code.
 */
public final class PluginCommandSpec {

    /** The command body: runs worker-side against the exec facade; returns the exit code. */
    public interface Body {
        int run(PluginCommandExec exec) throws Exception;
    }

    private final String name;
    private String description = "";
    private Body body;

    private PluginCommandSpec(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public static PluginCommandSpec named(String name) {
        return new PluginCommandSpec(name);
    }

    public PluginCommandSpec description(String description) {
        this.description = description == null ? "" : description;
        return this;
    }

    public PluginCommandSpec run(Body body) {
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
