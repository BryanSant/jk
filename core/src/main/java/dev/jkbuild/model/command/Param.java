// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model.command;

/**
 * A positional parameter, declared as data rather than a picocli
 * {@code @Parameters} annotation.
 *
 * @param name        display name (e.g. {@code "file"}, {@code "coord"})
 * @param description help text
 * @param arity       how many values it accepts (see {@link Arity})
 * @param hidden      true to omit from help
 */
public record Param(String name, String description, Arity arity, boolean hidden) {

    public static Param of(String name, Arity arity, String description) {
        return new Param(name, description, arity, false);
    }

    public Param hide() {
        return new Param(name, description, arity, true);
    }
}
