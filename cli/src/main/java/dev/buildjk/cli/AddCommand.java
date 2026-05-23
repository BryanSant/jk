// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.config.BuildJkEditor;
import dev.buildjk.model.Coordinate;
import dev.buildjk.model.Scope;
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
@Command(name = "add", description = "Add a dependency to build.jk")
public final class AddCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<coord>",
            description = "group:artifact:version (or group:artifact -- version required in v0.1)")
    String coord;

    // Mutually-exclusive scope flags. Default (no flag) = main.
    // Inlined rather than wrapped in @ArgGroup because picocli-codegen 4.7.7
    // can't generate native-image reflection config for that pattern; we
    // enforce exclusivity by hand below.
    @Option(names = "--test",      description = "Test scope.")
    boolean test;
    @Option(names = "--runtime",   description = "Runtime scope.")
    boolean runtime;
    @Option(names = "--provided",  description = "Provided scope.")
    boolean provided;
    @Option(names = "--processor", description = "Annotation processor scope.")
    boolean processor;

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path file = dir.resolve("jk.toml");
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
        int selected = (test ? 1 : 0) + (runtime ? 1 : 0)
                + (provided ? 1 : 0) + (processor ? 1 : 0);
        if (selected > 1) {
            System.err.println("jk add: --test / --runtime / --provided / --processor are mutually exclusive");
            return 64;
        }
        Scope scope = test ? Scope.TEST
                : runtime ? Scope.RUNTIME
                : provided ? Scope.PROVIDED
                : processor ? Scope.PROCESSOR
                : Scope.MAIN;
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

}
