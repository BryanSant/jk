// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.run.Pipeline;
import java.nio.file.Path;

/**
 * A module's assembled pipeline + the estimates a caller needs to render/calibrate — one entry of the
 * {@link WorkspaceBuildListener#onPlan} burst. Pure identity + presentation data: the engine keeps
 * its own {@code BuildUnit → ModulePlan} map internally, so no engine-internal type rides here
 * (slim-client Stage 5; the re-foundation's package-private {@code unit()} accessor is gone because
 * the field itself is).
 *
 * <p>On the engine-hosted path the {@link #pipeline()} is a client-side reconstruction built with inert
 * {@link build.jumpkick.run.Step}s — renderers read only {@code name()}/{@code steps()} and it is
 * never {@code run()}.
 */
public final class ModulePlan {
    private final Path dir;
    private final String coord;
    private final Pipeline pipeline;
    private final int weight;
    private final boolean fullyCached;
    private final Path cache;

    public ModulePlan(Path dir, String coord, Pipeline pipeline, int weight, boolean fullyCached, Path cache) {
        this.dir = dir;
        this.coord = coord;
        this.pipeline = pipeline;
        this.weight = weight;
        this.fullyCached = fullyCached;
        this.cache = cache;
    }

    /** Reconstruct a plan client-side from wire-level data (engine front-ends). */
    public static ModulePlan fromWire(Path dir, String coord, Pipeline pipeline, int weight, boolean fullyCached, Path cache) {
        return new ModulePlan(dir, coord, pipeline, weight, fullyCached, cache);
    }

    public String coord() {
        return coord;
    }

    public Path dir() {
        return dir;
    }

    public Pipeline pipeline() {
        return pipeline;
    }

    public int weight() {
        return weight;
    }

    public boolean fullyCached() {
        return fullyCached;
    }

    public Path cache() {
        return cache;
    }
}
