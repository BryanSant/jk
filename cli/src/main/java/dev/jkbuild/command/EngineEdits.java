// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Client side of the engine-hosted jk.toml edit contract ({@code EDIT_REQUEST},
 * docs/thin-client-plan.md): the client names the operation; the engine (the only component
 * holding a TOML stack) parses, edits, and writes. In-process twin under jk.test.noEngine.
 */
final class EngineEdits {

    private EngineEdits() {}

    /** Apply one edit; returns whether the file changed. Throws with a ready-to-print message. */
    static boolean apply(Path file, String op, List<String> args) throws IOException {
        if (Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"))) {
            var result = dev.jkbuild.cli.engine.InProcessEngine.require().edit(file, op, args);
            if (result[1] != null) throw new IOException(result[1]);
            return Boolean.parseBoolean(result[0]);
        }
        return dev.jkbuild.cli.engine.EngineClient.edit(
                dev.jkbuild.engine.EnginePaths.current(), file, op, args);
    }
}
