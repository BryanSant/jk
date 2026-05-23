// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.script;

import dev.buildjk.model.Dependency;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * The parsed metadata block of a single-file script (PRD §19).
 *
 * <p>Both jk-style ({@code //jk dep ...}) and JBang-style ({@code //DEPS},
 * {@code //JAVA}, {@code //JAVAC_OPTIONS}, {@code //JAVA_OPTIONS},
 * {@code //KOTLIN}) directives feed into the same record. The runner makes
 * no distinction once parsing is done.
 *
 * @param kotlinVersion explicit Kotlin compiler version (from {@code //KOTLIN}
 *     or {@code //jk kotlin}), or {@code null} to use the default installed
 *     distribution.
 */
public record ScriptHeader(
        List<Dependency> deps,
        Integer release,
        List<URI> repos,
        List<String> features,
        List<String> javacOptions,
        List<String> javaOptions,
        List<String> sources,
        String kotlinVersion) {

    public ScriptHeader {
        deps = List.copyOf(Objects.requireNonNull(deps, "deps"));
        repos = List.copyOf(Objects.requireNonNull(repos, "repos"));
        features = List.copyOf(Objects.requireNonNull(features, "features"));
        javacOptions = List.copyOf(Objects.requireNonNull(javacOptions, "javacOptions"));
        javaOptions = List.copyOf(Objects.requireNonNull(javaOptions, "javaOptions"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }

    public static ScriptHeader empty() {
        return new ScriptHeader(List.of(), null, List.of(), List.of(),
                List.of(), List.of(), List.of(), null);
    }
}
