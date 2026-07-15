// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.script;

import build.jumpkick.model.Dependency;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * The parsed metadata block of a single-file script (PRD §19).
 *
 * <p>Both jk-style ({@code //jk dep ...}) and JBang-style ({@code //DEPS}, {@code //REPOS}, {@code
 * //JAVA}, {@code //MAIN}, {@code //SOURCES}, {@code //FILES}, {@code //JAVAC_OPTIONS} /
 * {@code //COMPILE_OPTIONS}, {@code //JAVA_OPTIONS} / {@code //RUNTIME_OPTIONS}, {@code //PREVIEW},
 * {@code //KOTLIN}) directives feed into the same record — as do Kotlin's {@code @file:DependsOn} /
 * {@code @file:Repository} annotations for {@code .kt}/{@code .kts} scripts. The runner makes no
 * distinction once parsing is done.
 *
 * @param sources extra source files ({@code //SOURCES}), relative to the script's directory
 * @param files resources to materialize on the runtime classpath ({@code //FILES}), each {@code
 *     target=source} (or a bare name for target == source), source relative to the script's
 *     directory
 * @param main explicit main class ({@code //MAIN}), or {@code null} to derive from the file name
 * @param gav the script's self-declared coordinate ({@code //GAV}), informational
 * @param description one-line description ({@code //DESCRIPTION}), informational
 * @param kotlinVersion explicit Kotlin compiler version (from {@code //KOTLIN} or {@code //jk
 *     kotlin}), or {@code null} to use the default installed distribution.
 */
public record ScriptHeader(
        List<Dependency> deps,
        Integer release,
        List<URI> repos,
        List<String> features,
        List<String> javacOptions,
        List<String> javaOptions,
        List<String> sources,
        List<String> files,
        String main,
        String gav,
        String description,
        String kotlinVersion) {

    public ScriptHeader {
        deps = List.copyOf(Objects.requireNonNull(deps, "deps"));
        repos = List.copyOf(Objects.requireNonNull(repos, "repos"));
        features = List.copyOf(Objects.requireNonNull(features, "features"));
        javacOptions = List.copyOf(Objects.requireNonNull(javacOptions, "javacOptions"));
        javaOptions = List.copyOf(Objects.requireNonNull(javaOptions, "javaOptions"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        files = List.copyOf(Objects.requireNonNull(files, "files"));
    }

    public static ScriptHeader empty() {
        return new ScriptHeader(
                List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null,
                null);
    }
}
