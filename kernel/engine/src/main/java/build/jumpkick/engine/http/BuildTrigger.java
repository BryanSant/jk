// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.http;

/**
 * The seam {@code POST /api/build} triggers through — implemented by {@code EngineServer}, which
 * runs the build on the same {@code BuildService} path the socket protocol uses. Fire-and-observe:
 * the returned request id is acknowledged with {@code 202}, and progress arrives on {@code
 * /api/events}, never on the POST response. See {@code docs/http.md}.
 */
@FunctionalInterface
public interface BuildTrigger {

    /**
     * Start a build of the workspace/project at {@code dir} (an absolute path containing {@code
     * jk.toml}) and return its request id immediately.
     *
     * @throws IllegalArgumentException when {@code dir} isn't buildable — relayed as a {@code 400}
     */
    long trigger(String dir);
}
