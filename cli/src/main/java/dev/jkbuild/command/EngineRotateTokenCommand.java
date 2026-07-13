// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import java.io.IOException;
import java.util.List;

/**
 * {@code jk engine rotate-token} — invalidate the embedded HTTP server's bearer token
 * ({@code docs/http.md}). The token is otherwise persisted and stable across restarts so open
 * dashboard tabs keep working; rotation is the explicit escape hatch for when you actually want to
 * revoke it (say, you shared a tokenized URL and want to lock it out).
 *
 * <p>Revocation has two halves: delete the persisted token file, and stop any running engine so its
 * in-memory copy stops being honored. The next {@code jk} command spawns a fresh engine that mints a
 * new token; {@code jk engine status} prints the new tokenized URL.
 */
public final class EngineRotateTokenCommand implements CliCommand {

    @Override
    public String name() {
        return "rotate-token";
    }

    @Override
    public String description() {
        return "Invalidate and regenerate the HTTP dashboard token";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        EnginePaths.Paths paths = EnginePaths.current();
        try {
            java.nio.file.Files.deleteIfExists(paths.httpToken());
        } catch (IOException e) {
            CliOutput.err("jk engine: could not remove the token file (" + e.getMessage() + ")");
            return Exit.SOFTWARE;
        }
        // A running engine still holds the old token in memory — stop it so the old value is
        // genuinely revoked, not just replaced on disk. The next command respawns and mints fresh.
        if (EngineClient.ping(paths.socket()) && !EngineClient.stop(paths.socket())) {
            CliOutput.err("jk engine: token file removed, but stopping the running engine failed;"
                    + " run 'jk engine stop' so the old token stops being accepted");
            return Exit.SOFTWARE;
        }
        CliOutput.out("jk engine: token rotated."
                + " The next command spawns an engine with a fresh token —"
                + " run 'jk engine status' for the new dashboard URL.");
        return Exit.SUCCESS;
    }
}
