// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.hocon.BuildJkEditor;
import dev.buildjk.model.Coordinate;
import dev.buildjk.model.Scope;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk add &lt;coord&gt;} — add a dependency to {@code build.jk}.
 *
 * <p>v0.1 scope: only modifies the file; full resolution / fetch lands
 * with the resolver milestone. Until then the command prints a hint to
 * run {@code jk lock} (also yet to be implemented).
 */
@Command(name = "add", description = "Add a dependency to build.jk.")
public final class AddCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<coord>",
            description = "group:artifact:version (or group:artifact -- version required in v0.1)")
    String coord;

    @ArgGroup
    ScopeFlags scopeFlags;

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path file = dir.resolve("build.jk");
        if (!Files.exists(file)) {
            System.err.println("jk add: no build.jk in current directory");
            return 2; // EX_CONFIG
        }
        Coordinate parsed;
        try {
            parsed = Coordinate.parse(coord);
        } catch (IllegalArgumentException e) {
            System.err.println("jk add: " + e.getMessage());
            return 64; // EX_USAGE
        }
        Scope scope = scopeFlags != null ? scopeFlags.scope() : Scope.MAIN;
        String original = Files.readString(file);
        String updated;
        try {
            updated = BuildJkEditor.addDependency(
                    original,
                    scope,
                    parsed.module(),
                    parsed.version());
        } catch (IllegalStateException e) {
            System.err.println("jk add: " + e.getMessage());
            return 1;
        }
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        System.out.println("Added " + parsed.module() + " " + parsed.version()
                + " to dependencies." + scope.canonical());
        System.out.println("Run `jk lock` to resolve (not yet implemented).");
        return 0;
    }

    /** Mutually-exclusive scope flags. Default (no flag) = main. */
    static final class ScopeFlags {
        @Option(names = "--test",      description = "Test scope.")      boolean test;
        @Option(names = "--runtime",   description = "Runtime scope.")   boolean runtime;
        @Option(names = "--provided",  description = "Provided scope.")  boolean provided;
        @Option(names = "--processor", description = "Annotation processor scope.") boolean processor;

        Scope scope() {
            if (test) return Scope.TEST;
            if (runtime) return Scope.RUNTIME;
            if (provided) return Scope.PROVIDED;
            if (processor) return Scope.PROCESSOR;
            return Scope.MAIN;
        }
    }
}
