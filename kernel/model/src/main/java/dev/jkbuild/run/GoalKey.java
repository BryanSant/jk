// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

import java.util.Objects;

/**
 * Typed name for a value stashed in a Goal's shared state. Lets phases hand data downstream without
 * casting and without a bespoke holder class per command.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * static final GoalKey<Lockfile> LOCKFILE = GoalKey.of("lockfile", Lockfile.class);
 *
 * // upstream phase:
 * ctx.put(LOCKFILE, parsed);
 *
 * // downstream phase:
 * Lockfile lock = ctx.require(LOCKFILE);
 * }</pre>
 *
 * <p>Keys are simple {@code (name, type)} pairs — no global registry, no Spring-style application
 * context. Two keys with the same name are considered the same slot; type is only used for the cast
 * on {@code get}/{@code require}.
 */
public record GoalKey<T>(String name, Class<T> type) {

    public GoalKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    public static <T> GoalKey<T> of(String name, Class<T> type) {
        return new GoalKey<>(name, type);
    }
}
