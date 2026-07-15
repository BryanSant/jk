// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.run;

import java.util.Objects;

/**
 * Typed name for a value stashed in a Pipeline's shared state. Lets steps hand data downstream without
 * casting and without a bespoke holder class per command.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * static final PipelineKey<Lockfile> LOCKFILE = PipelineKey.of("lockfile", Lockfile.class);
 *
 * // upstream step:
 * ctx.put(LOCKFILE, parsed);
 *
 * // downstream step:
 * Lockfile lock = ctx.require(LOCKFILE);
 * }</pre>
 *
 * <p>Keys are simple {@code (name, type)} pairs — no global registry, no Spring-style application
 * context. Two keys with the same name are considered the same slot; type is only used for the cast
 * on {@code get}/{@code require}.
 */
public record PipelineKey<T>(String name, Class<T> type) {

    public PipelineKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    public static <T> PipelineKey<T> of(String name, Class<T> type) {
        return new PipelineKey<>(name, type);
    }
}
